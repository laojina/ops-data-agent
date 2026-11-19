package com.opsdata.agent;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
final class AskService {

    private static final int MAX_STEPS = 6;
    private static final int MAX_SQL_ATTEMPTS = 3;
    private static final String LOOKUP = "lookup_metric";
    private static final String LIST = "list_tables";
    private static final String DESCRIBE = "describe_table";
    private static final String RUN = "run_sql";
    private static final String NEED_CLARIFY = "NEED_CLARIFY:";
    private static final String NO_EVIDENCE = "INSUFFICIENT_EVIDENCE:";

    private static final String SYSTEM_PROMPT = """
            你是经营数据问答助手。本轮只能使用已注册工具的能力，遵守每个工具的描述。
            口径问题先用 lookup_metric。查数时先取得一个指标口径和所需表结构，再生成 SQLite SELECT。
            若能确定表名，可以在同一步并行查询口径和表结构。未知表名时使用 list_tables。
            等真实的工具结果返回后再写 SQL，按字典中的 time_field、numerator 和 denominator 计算。
            保留分母中的零异常记录和应计订单，留意连接导致重复计数。比率可在 SQL 中换算成百分比并注明单位。
            SQL 必须为单条只读查询，最外层带整数 LIMIT，结果列使用清楚的中文别名。
            查数时只用查询返回的值。若出现 SQL 错误，根据工具给出的错误和字段信息修复。已有正确结果就结束。
            样例库覆盖 2026-09-01 至 2026-10-31。题目中无年份的月份按 2026 年理解。
            若查询范围在覆盖区间之外、没有数据、字段不存在、用户要求修改数据，或工具描述不支持该任务，
            用 INSUFFICIENT_EVIDENCE: 开头并说明原因，不输出估算值。
            若用户未说清要查的指标或必要条件，用 NEED_CLARIFY: 开头并提出一个简短问题。
            """;

    private final ObjectMapper mapper;
    private final ModelClient model;
    private final MetricDictionary dictionary;
    private final ReadOnlyDatabase database;
    private final ArrayNode tools;

    AskService(ObjectMapper mapper, ModelClient model, MetricDictionary dictionary, ReadOnlyDatabase database,
               @Value("${agent.tools.run-sql-description:根据已读取的表结构和指标字典执行一条带 LIMIT 的只读 SQLite 查询。返回列名、最多 200 行及是否截断。}") String sqlDescription) {
        this.mapper = mapper;
        this.model = model;
        this.dictionary = dictionary;
        this.database = database;
        this.tools = mapper.createArrayNode();
        register(LOOKUP, "按名称、别名或 ID 查询指标字典，回答口径或生成指标查询前使用。", "term");
        register(LIST, "列出数据库中的经营表、行数和用途。表名不确定时使用。", null);
        register(DESCRIBE, "从 SQLite 读取指定表的字段、类型、可用维度和枚举值。写 SQL 前使用。", "name");
        register(RUN, sqlDescription, "sql");
    }

    Answer ask(String question) {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", question);
        Turn turn = new Turn();

        // 一步是模型的一次决策和它发出的工具调用，trace 按实际调用分别记录。
        for (int step = 1; step <= MAX_STEPS; step++) {
            Map<String, Object> input = Map.of("step", step, "messages", messages.deepCopy(), "tools", tools);
            long modelStart = System.nanoTime();
            ModelClient.Completion completion;
            try {
                completion = model.complete(messages, tools);
            } catch (IOException exception) {
                turn.trace.add(new Trace("model", model.model(), input, exception.getMessage(), null, elapsed(modelStart)));
                return turn.answer("error", exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                String error = "模型调用被中断。";
                turn.trace.add(new Trace("model", model.model(), input, error, null, elapsed(modelStart)));
                return turn.answer("error", error);
            }

            JsonNode message = completion.message();
            JsonNode calls = message.path("tool_calls");
            String content = message.path("content").asText("").strip();
            boolean hasCalls = calls.isArray() && !calls.isEmpty();
            turn.trace.add(new Trace("model", completion.model(), input, hasCalls ? calls : content,
                    completion.usage(), elapsed(modelStart)));
            if (!hasCalls) {
                return finish(turn, content);
            }
            if (step == MAX_STEPS || calls.size() > tools.size()) {
                break;
            }

            // 同一批调用中的口径和表结构要先回传给模型，它在下一步才可以生成查询。
            boolean readyToQuery = turn.metricIds.size() == 1 && turn.schemaRead;
            messages.add(message);
            for (JsonNode call : calls) {
                String id = call.path("id").asText("");
                if (id.isBlank()) {
                    return turn.answer("error", "模型的工具调用缺少 ID。");
                }
                String name = call.path("function").path("name").asText("");
                long toolStart = System.nanoTime();
                JsonNode arguments = parseArguments(call.path("function").path("arguments").asText(""));
                ObjectNode outcome = executeTool(name, arguments, readyToQuery, turn);
                turn.trace.add(new Trace("tool", name, arguments, outcome, null, elapsed(toolStart)));
                messages.addObject().put("role", "tool").put("tool_call_id", id).put("content", outcome.toString());
            }
        }
        return turn.answer("error", "已达到本轮调用上限。");
    }

    private ObjectNode executeTool(String name, JsonNode arguments, boolean readyToQuery, Turn turn) {
        ObjectNode outcome = mapper.createObjectNode();
        if (arguments == null || !arguments.isObject()) {
            return outcome.put("error", "工具参数应是有效的 JSON 对象。");
        }
        try {
            switch (name) {
                case LOOKUP -> {
                    JsonNode metric = dictionary.lookup(requiredText(arguments, "term")).orElse(null);
                    outcome.set("metric", metric);
                    if (metric != null) {
                        turn.metricIds.put(metric.path("id").asText(), metric.path("name").asText());
                    }
                }
                case LIST -> outcome.set("tables", mapper.valueToTree(database.listTables()));
                case DESCRIBE -> {
                    outcome.set("table", mapper.valueToTree(database.describe(requiredText(arguments, "name"))));
                    turn.schemaRead = true;
                }
                case RUN -> runQuery(turn, outcome, requiredText(arguments, "sql"), readyToQuery);
                default -> outcome.put("error", "未知工具：" + name);
            }
        } catch (IllegalArgumentException | SQLException exception) {
            outcome.put("error", exception.getMessage());
            if (RUN.equals(name)) {
                turn.lastSqlError = exception.getMessage();
            }
        } catch (IOException exception) {
            outcome.put("error", "无法读取指标字典。");
        }
        return outcome;
    }

    private void runQuery(Turn turn, ObjectNode outcome, String sql, boolean readyToQuery) throws SQLException {
        if (!readyToQuery) {
            throw new IllegalArgumentException("请先获取一个口径和表结构，再根据工具结果编写 SQL。");
        }
        if (turn.attempts >= MAX_SQL_ATTEMPTS) {
            throw new IllegalArgumentException("本轮 SQL 已达到初次执行加两次重写的上限。");
        }
        turn.attempts++;
        try {
            var result = database.run(sql);
            turn.sql.add(sql);
            turn.result = result;
            turn.lastSqlError = null;
            outcome.set("columns", mapper.valueToTree(result.columns()));
            outcome.set("rows", mapper.valueToTree(result.rows()));
            outcome.put("truncated", result.truncated());
        } catch (SQLException exception) {
            // 保留这次提交给 SQLite 的原句和错误，模型收到后可以修改。
            turn.sql.add(sql);
            throw exception;
        }
    }

    private Answer finish(Turn turn, String content) {
        if (turn.lastSqlError != null) {
            return turn.answer("error", "SQL 查询未成功：" + turn.lastSqlError);
        }
        if (content.startsWith(NEED_CLARIFY)) {
            return turn.answer("need_clarify", content.substring(NEED_CLARIFY.length()).strip());
        }
        if (content.startsWith(NO_EVIDENCE)) {
            return turn.answer("insufficient_evidence", content.substring(NO_EVIDENCE.length()).strip());
        }
        if (turn.result != null) {
            if (turn.result.rows().stream().flatMap(List::stream).noneMatch(Objects::nonNull)) {
                return turn.answer("insufficient_evidence", "本次查询没有返回可用的统计值。");
            }
            return turn.answer("answered", summarizeRows(turn));
        }
        if (turn.metricIds.isEmpty()) {
            return turn.answer("insufficient_evidence", "本次没有从工具获得可用的指标依据。");
        }
        if (content.isBlank()) {
            return turn.answer("error", "模型没有返回可展示的答复。");
        }
        return turn.answer("answered", content);
    }

    private String summarizeRows(Turn turn) {
        var result = turn.result;
        // 查数答复中的数字直接取自这次结果，模型的自然语言总结保留在 trace。
        String preview = result.rows().stream().limit(5).map(row ->
                IntStream.range(0, result.columns().size())
                        .mapToObj(index -> result.columns().get(index) + "：" + Objects.toString(row.get(index), "无数据"))
                        .collect(Collectors.joining("，")))
                .collect(Collectors.joining("\n"));
        String name = turn.metricIds.values().iterator().next();
        return name + "查询结果\n" + preview + (result.rows().size() > 5 ? "\n完整返回值见下方表格。" : "");
    }

    private void register(String name, String description, String parameter) {
        ObjectNode function = tools.addObject().put("type", "function").putObject("function")
                .put("name", name).put("description", description);
        ObjectNode schema = function.putObject("parameters").put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        if (parameter != null) {
            properties.putObject(parameter).put("type", "string");
            schema.putArray("required").add(parameter);
        }
    }

    private JsonNode parseArguments(String arguments) {
        try {
            return mapper.readTree(arguments);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String requiredText(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("工具参数 " + name + " 必须是非空字符串。");
        }
        return value.textValue();
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static final class Turn {
        private final List<Trace> trace = new ArrayList<>();
        private final List<String> sql = new ArrayList<>();
        private final Map<String, String> metricIds = new LinkedHashMap<>();
        private boolean schemaRead;
        private int attempts;
        private String lastSqlError;
        private ReadOnlyDatabase.QueryResult result;

        private Answer answer(String status, String content) {
            String metricId = metricIds.size() == 1 ? metricIds.keySet().iterator().next() : null;
            Table table = result == null || !"answered".equals(status)
                    ? new Table(List.of(), List.of(), false)
                    : new Table(result.columns(), result.rows(), result.truncated());
            return new Answer(status, content, sql, metricId, table, trace);
        }
    }

    record Answer(String status, String answer, List<String> sql, @JsonProperty("metric_id") String metricId,
                  Table table, List<Trace> trace) {}

    record Table(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    record Trace(String type, String name, Object input, Object outcome, JsonNode tokens,
                 @JsonProperty("latency_ms") long latencyMs) {}
}
