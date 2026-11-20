package com.opsdata.agent;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
    private static final int MAX_HISTORY = 6;
    private static final String DEFAULT_METRIC = "DEFAULT_METRIC:";
    private static final String LOOKUP = "lookup_metric";
    private static final String LIST = "list_tables";
    private static final String DESCRIBE = "describe_table";
    private static final String RUN = "run_sql";
    private static final String FULL_SQL_DESCRIPTION =
            "根据已读取的表结构和指标字典执行一条带 LIMIT 的只读 SQLite 查询。返回列名、最多 200 行及是否截断。";
    private static final String COUNTS_ONLY_DESCRIPTION = "仅用于统计表行数。请使用 list_tables 查看行数。";
    private static final String NEED_CLARIFY = "NEED_CLARIFY:";
    private static final String NO_EVIDENCE = "INSUFFICIENT_EVIDENCE:";
    private static final Pattern VAGUE_SHIPMENT = Pattern.compile("怎么样|如何|好不好|好坏|表现|情况");
    private static final Pattern PHONE_FIELDS = Pattern.compile("手机号|手机号码|联系电话|客户电话");

    private static final String SYSTEM_PROMPT = """
            你是经营数据问答助手。本轮只能使用已注册工具的能力，遵守每个工具的描述。
            本轮 run_sql 的工具描述是：「%s」
            查数前先判断这个描述是否允许用户请求。这项能力判断先于下面的查数流程。
            若描述只允许统计表行数，出库及时率、运输异常率等经营指标超出能力范围，直接用
            INSUFFICIENT_EVIDENCE: 说明原因，无需获取口径、表结构或尝试 SQL。
            口径问题先用 lookup_metric，最终回答以 METRIC_DEFINITION: 开头。
            查字典只传指标名称、别名或 ID，日期、区域和仓库名称留给 SQL 条件。
            查数时先取得一个指标口径和所需表结构，再生成 SQLite SELECT。
            若能确定表名，可以在同一步并行查询口径和表结构。未知表名时使用 list_tables。
            等真实的工具结果返回后再写 SQL，按字典中的 time_field、numerator 和 denominator 计算。
            先确定字典分母的订单或记录集合，再计算分子。若分子有异常类型筛选或一对多关联，
            可先独立聚合或用 EXISTS，避免用分子的筛选条件误缩小分母。分母以 denominator 字段为准。比率可在 SQL 中换算成百分比并注明单位。
            SQL 必须为单条只读查询，最外层带整数 LIMIT，中文结果列别名用双引号包裹。
            用仓库名或区域过滤时关联 warehouses。不要猜仓库 ID。
            status 表示订单当前节点，已签收订单也已出库。字典以 shipped_at 非空代表已出库时，
            按该时间字段筛选。除非用户或字典要求具体状态，勿加 status 等于已出库的条件。
            比率须由 SQL 计算并返回。SQLite 整数除法会截断，请先乘 1.0（百分比先乘 100.0）再除，
            并用 NULLIF 处理零分母。不可先用两个整数相除再转浮点或乘 100。
            查数时只用查询返回的值。若出现 SQL 错误，根据工具给出的错误和字段信息修复。已有正确结果就结束。
            样例库观测范围为 2026-09-01 至 2026-10-31。题目中无年份的日期按 2026 年理解。
            选择统计期时用字典规定的时间字段，统计期只使用观测范围内的数据，不能把尚未观察的日期当作零值。
            若查询范围在覆盖区间之外、没有数据、字段不存在、用户要求修改数据，或工具描述不支持该任务，
            用 INSUFFICIENT_EVIDENCE: 开头并说明原因，不输出估算值。
            上文仅用于理解指标、时间和筛选条件。追问只改变明确提到的条件，继承其他条件并重新查询。
            若指标不明确，且上一轮未澄清，用 NEED_CLARIFY: 开头。反问时复述已经识别的时间范围，
            对出库问题给出出库及时率、出库票量、运输异常率三个选项。不要追问已知条件。
            若上一轮已经澄清，要保留时间等条件并执行。仍未选定指标时默认 M01（出库及时率），
            先从字典取口径，完成查询后以 DEFAULT_METRIC: 开头并解释。澄清后未指定仓库或区域时按仓库分组。
            """;

    private final ObjectMapper mapper;
    private final ModelClient model;
    private final MetricDictionary dictionary;
    private final ReadOnlyDatabase database;
    private final ArrayNode tools;
    private final String systemPrompt;
    private final LocalDate fixedDate;
    private final boolean tableCountsOnly;
    private final Map<String, Deque<Exchange>> sessions = new ConcurrentHashMap<>();

    AskService(ObjectMapper mapper, ModelClient model, MetricDictionary dictionary, ReadOnlyDatabase database,
               @Value("${agent.tools.run-sql-description:}") String sqlDescription,
               @Value("${agent.tools.table-counts-only:false}") boolean tableCountsOnly,
               @Value("${agent.today:}") String dateOverride) {
        this.mapper = mapper;
        this.model = model;
        this.dictionary = dictionary;
        this.database = database;
        this.fixedDate = dateOverride.isBlank() ? null : LocalDate.parse(dateOverride);
        this.tableCountsOnly = tableCountsOnly;
        this.tools = mapper.createArrayNode();
        register(LOOKUP, "按名称、别名或 ID 查询指标字典，回答口径或生成指标查询前使用。", "term");
        register(LIST, "列出数据库中的经营表、行数和用途。表名不确定时使用。", null);
        register(DESCRIBE, "从 SQLite 读取指定表的字段、类型、可用维度和枚举值。写 SQL 前使用。", "name");
        if (sqlDescription.isBlank()) {
            sqlDescription = tableCountsOnly ? COUNTS_ONLY_DESCRIPTION : FULL_SQL_DESCRIPTION;
        }
        this.systemPrompt = SYSTEM_PROMPT.formatted(sqlDescription);
        register(RUN, sqlDescription, tableCountsOnly ? null : "sql");
    }

    Answer ask(String sessionId, String question) {
        Deque<Exchange> history = sessions.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        // 同一会话的两个请求顺序处理，其他会话可独立执行。
        synchronized (history) {
            Answer answer = askWithHistory(question, history);
            if (!"error".equals(answer.status())) {
                if (history.size() == MAX_HISTORY) {
                    history.removeFirst();
                }
                String clarification = "need_clarify".equals(answer.status()) ? answer.answer() : "";
                history.addLast(new Exchange(question, answer.metricId(), clarification));
            }
            return answer;
        }
    }

    private Answer askWithHistory(String question, Deque<Exchange> history) {
        ArrayNode messages = mapper.createArrayNode();
        LocalDate today = fixedDate == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : fixedDate;
        boolean awaitingClarification = !history.isEmpty() && !history.getLast().clarification().isEmpty();
        QuestionScope scope = QuestionScope.read(question, today);
        if (scope.periods().isEmpty() && scope.invalidDate() == null && awaitingClarification) {
            scope = QuestionScope.read(history.getLast().question(), today);
        }
        String missingDateEvidence = scope.missingEvidence();
        boolean vagueShipment = question.contains("出库") && VAGUE_SHIPMENT.matcher(question).find();
        boolean mentionsMetric = (vagueShipment || awaitingClarification) && mentionsMetric(question);
        boolean mustClarify = !awaitingClarification && vagueShipment && !mentionsMetric
                && history.stream().noneMatch(prior -> prior.metricId() != null);
        boolean useDefault = awaitingClarification && !mentionsMetric;
        boolean missingSensitiveField = PHONE_FIELDS.matcher(question).find();
        String hints = "当前业务日期（北京时间）为 " + today + "。";
        if (!scope.periods().isEmpty()) {
            hints += "本次已识别的时间范围为 " + scope.label() + "。";
        }
        if (missingDateEvidence != null || mustClarify || missingSensitiveField) {
            hints += "本轮无需调用工具，请说明数据范围或向用户澄清指标。";
        } else if (awaitingClarification) {
            hints += "本轮回答上一轮的澄清。承接上月或已识别的时间，按仓库分组。";
            if (useDefault) {
                hints += "用户还未选指标，直接用默认口径 M01 查询并注明原因。";
            }
        }
        if (tableCountsOnly) {
            hints += "本次查数只能用 list_tables 获取表行数，run_sql 不接收 SQL。";
        }
        messages.addObject().put("role", "system").put("content", systemPrompt + hints);
        for (Exchange prior : history) {
            messages.addObject().put("role", "user").put("content", prior.question());
            String reply = prior.clarification().isEmpty()
                    ? "上一轮指标 ID：" + Objects.toString(prior.metricId(), "未确定")
                    : prior.clarification();
            // 保留提问和口径标识，避免把上次结果里的数字作为本轮依据。
            messages.addObject().put("role", "assistant").put("content", reply);
        }
        messages.addObject().put("role", "user").put("content", question);
        Turn turn = new Turn();
        turn.useDefault = useDefault;

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
            turn.trace.add(new Trace("model", completion.model(), input, hasCalls ? calls : "模型已返回文本答复",
                    completion.usage(), elapsed(modelStart)));
            // 先记录真实模型响应，再执行可验证的边界判断，跳过不应运行的 SQL。
            if (missingSensitiveField) {
                return turn.answer("insufficient_evidence", "样例库没有客户手机号字段，无法导出。");
            }
            if (missingDateEvidence != null) {
                return turn.answer("insufficient_evidence", missingDateEvidence);
            }
            if (mustClarify) {
                String period = scope.periods().isEmpty() ? "" : scope.label() + "的";
                return turn.answer("need_clarify", "你想看" + period + "出库及时率、出库票量，还是运输异常率？");
            }
            if (!hasCalls) {
                if (awaitingClarification && content.startsWith(NEED_CLARIFY)) {
                    if (turn.defaultRequested || step == MAX_STEPS) {
                        return turn.answer("insufficient_evidence", "已选择默认口径出库及时率，当前条件不足以完成查询。");
                    }
                    turn.defaultRequested = true;
                    messages.add(message);
                    messages.addObject().put("role", "system").put("content",
                            "本轮已用过一次澄清。保留上文时间和条件，按默认口径 M01（出库及时率）继续查询。");
                    continue;
                }
                return finish(turn, content);
            }
            if (step == MAX_STEPS || calls.size() > tools.size()) {
                break;
            }

            // 同一批调用中的口径和表结构要先回传给模型，它在下一步才可以生成查询。
            boolean readyToQuery = turn.metrics.size() == 1 && turn.schemaRead;
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
                long toolLatency = elapsed(toolStart);
                if (RUN.equals(name) && outcome.has("rows")) {
                    outcome.put("latency_ms", toolLatency);
                }
                turn.trace.add(new Trace("tool", name, arguments, outcome, null, toolLatency));
                messages.addObject().put("role", "tool").put("tool_call_id", id).put("content", outcome.toString());
            }
        }
        if (tableCountsOnly) {
            return insufficientToolCapability(turn);
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
                        turn.metrics.put(metric.path("id").asText(), metric);
                    }
                }
                case LIST -> outcome.set("tables", mapper.valueToTree(database.listTables()));
                case DESCRIBE -> {
                    outcome.set("table", mapper.valueToTree(database.describe(requiredText(arguments, "name"))));
                    turn.schemaRead = true;
                }
                case RUN -> {
                    if (tableCountsOnly) {
                        turn.queryLimited = true;
                        outcome.put("error", "当前工具仅用于统计表行数。请使用 list_tables，无法执行指标 SQL。");
                    } else {
                        runQuery(turn, outcome, requiredText(arguments, "sql"), readyToQuery);
                    }
                }
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
        if (turn.queryLimited) {
            return insufficientToolCapability(turn);
        }
        if (turn.lastSqlError != null) {
            return turn.answer("error", "SQL 查询未成功：" + turn.lastSqlError);
        }
        if (content.startsWith(NEED_CLARIFY)) {
            return turn.answer("need_clarify", content.substring(NEED_CLARIFY.length()).strip());
        }
        if (content.startsWith(NO_EVIDENCE)) {
            if (tableCountsOnly) {
                return insufficientToolCapability(turn);
            }
            return turn.answer("insufficient_evidence", content.substring(NO_EVIDENCE.length()).strip());
        }
        if (turn.result != null) {
            if (turn.result.rows().stream().flatMap(List::stream).noneMatch(Objects::nonNull)) {
                return turn.answer("insufficient_evidence", "本次查询没有返回可用的统计值。");
            }
            String result = summarizeRows(turn);
            if ((turn.useDefault || turn.defaultRequested || content.startsWith(DEFAULT_METRIC)) && turn.metrics.containsKey("M01")) {
                result = "你还没有选定口径，本次默认按出库及时率衡量出库时效。\n" + result;
            }
            return turn.answer("answered", result);
        }
        if (content.startsWith("METRIC_DEFINITION:") && turn.metrics.size() == 1) {
            JsonNode metric = turn.metrics.values().iterator().next();
            return turn.answer("answered", metric.path("definition").asText() + "\n分子：" + metric.path("numerator").asText()
                    + "\n分母：" + metric.path("denominator").asText("无"));
        }
        if (tableCountsOnly) {
            return insufficientToolCapability(turn);
        }
        return turn.answer("insufficient_evidence", "本次没有查询结果可作为答案依据。");
    }

    private Answer insufficientToolCapability(Turn turn) {
        return turn.answer("insufficient_evidence", "目前的查询工具仅支持统计表行数，无法计算该指标。");
    }

    private String summarizeRows(Turn turn) {
        var result = turn.result;
        // 查数答复中的数字直接取自这次结果，避免引用模型自行总结的数字。
        String preview = result.rows().stream().limit(5).map(row ->
                IntStream.range(0, result.columns().size())
                        .mapToObj(index -> result.columns().get(index) + "：" + Objects.toString(row.get(index), "无数据"))
                        .collect(Collectors.joining("，")))
                .collect(Collectors.joining("\n"));
        String name = turn.metrics.values().iterator().next().path("name").asText();
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

    private boolean mentionsMetric(String question) {
        try {
            return dictionary.mentionsMetric(question);
        } catch (IOException exception) {
            return false;
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

    private record Exchange(String question, String metricId, String clarification) {}

    private static final class Turn {
        private boolean defaultRequested;
        private boolean useDefault;
        private boolean queryLimited;
        private final List<Trace> trace = new ArrayList<>();
        private final List<String> sql = new ArrayList<>();
        private final Map<String, JsonNode> metrics = new LinkedHashMap<>();
        private boolean schemaRead;
        private int attempts;
        private String lastSqlError;
        private ReadOnlyDatabase.QueryResult result;

        private Answer answer(String status, String content) {
            String metricId = metrics.size() == 1 ? metrics.keySet().iterator().next() : null;
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
