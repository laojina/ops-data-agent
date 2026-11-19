package com.opsdata.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
final class AskService {

    private static final int MAX_STEPS = 6;
    private static final String LOOKUP_METRIC = "lookup_metric";
    private static final String SYSTEM_PROMPT = """
            你是经营指标助手，目前只回答指标口径、分子、分母和可用维度。
            回答有关口径的问题前，使用 lookup_metric 获取本次依据，根据工具结果用中文简短作答。
            找不到指标时说明缺少口径依据。当前没有实际查数能力，请勿估计、示例化或提供任何业务统计值。
            """;

    private final ObjectMapper mapper;
    private final ModelClient model;
    private final MetricDictionary dictionary;
    private final ArrayNode tools;

    AskService(ObjectMapper mapper, ModelClient model, MetricDictionary dictionary) {
        this.mapper = mapper;
        this.model = model;
        this.dictionary = dictionary;
        this.tools = mapper.createArrayNode();

        ObjectNode function = tools.addObject().put("type", "function").putObject("function")
                .put("name", LOOKUP_METRIC)
                .put("description", "按名称、别名或 ID 查询指标字典，回答口径、分子、分母和统计维度时使用。");
        ObjectNode parameters = function.putObject("parameters").put("type", "object");
        parameters.putObject("properties").putObject("term").put("type", "string")
                .put("description", "用户要查询的指标名称、别名或 ID");
        parameters.putArray("required").add("term");
    }

    Answer ask(String question) {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", question);
        List<Trace> trace = new ArrayList<>();
        Set<String> metricIds = new LinkedHashSet<>();

        while (trace.size() < MAX_STEPS) {
            Map<String, Object> input = Map.of("messages", messages.deepCopy(), "tools", tools);
            long modelStart = System.nanoTime();
            ModelClient.Completion completion;
            try {
                completion = model.complete(messages, tools);
            } catch (IOException exception) {
                trace.add(new Trace("model", model.model(), input, exception.getMessage(), null, elapsed(modelStart)));
                return answer("error", exception.getMessage(), metricIds, trace);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                String error = "模型调用被中断。";
                trace.add(new Trace("model", model.model(), input, error, null, elapsed(modelStart)));
                return answer("error", error, metricIds, trace);
            }

            JsonNode message = completion.message();
            JsonNode calls = message.path("tool_calls");
            String content = message.path("content").asText("");
            boolean hasCalls = calls.isArray() && !calls.isEmpty();
            trace.add(new Trace("model", completion.model(), input, hasCalls ? calls : content,
                    completion.usage(), elapsed(modelStart)));
            if (!hasCalls) {
                if (metricIds.isEmpty()) {
                    return answer("insufficient_evidence", "本次没有从字典获得可用的指标口径。", metricIds, trace);
                }
                if (content.isBlank()) {
                    return answer("error", "模型没有返回可展示的答复。", metricIds, trace);
                }
                return answer("answered", content, metricIds, trace);
            }
            if (trace.size() + calls.size() >= MAX_STEPS) {
                break;
            }

            // 保留模型发来的工具请求，再用相同的 ID 回传每条工具结果。
            messages.add(message);
            for (JsonNode call : calls) {
                String id = call.path("id").asText("");
                if (id.isBlank()) {
                    return answer("error", "模型的工具调用缺少 ID。", metricIds, trace);
                }
                String name = call.path("function").path("name").asText("");
                long toolStart = System.nanoTime();
                ObjectNode toolInput = mapper.createObjectNode();
                ObjectNode outcome = mapper.createObjectNode();

                try {
                    JsonNode arguments = mapper.readTree(call.path("function").path("arguments").asText(""));
                    String term = arguments == null ? "" : arguments.path("term").asText("").strip();
                    toolInput.put("term", term);
                    if (!LOOKUP_METRIC.equals(name) || term.isBlank()) {
                        outcome.put("error", "工具名称或指标参数无效。");
                    } else {
                        JsonNode metric = dictionary.lookup(term).orElse(null);
                        outcome.set("metric", metric);
                        if (metric != null) {
                            metricIds.add(metric.path("id").asText());
                        }
                    }
                } catch (JsonProcessingException exception) {
                    outcome.put("error", "无法解析工具参数或指标字典。");
                } catch (IOException exception) {
                    outcome.put("error", "无法读取指标字典。");
                }

                trace.add(new Trace("tool", name, toolInput, outcome, null, elapsed(toolStart)));
                messages.addObject().put("role", "tool").put("tool_call_id", id).put("content", outcome.toString());
            }
        }

        return answer("error", "已达到本轮的六步调用上限。", metricIds, trace);
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static Answer answer(String status, String content, Set<String> metricIds, List<Trace> trace) {
        String metricId = metricIds.size() == 1 ? metricIds.iterator().next() : null;
        return new Answer(status, content, List.of(), metricId, new Table(List.of(), List.of()), trace);
    }

    record Answer(String status, String answer, List<String> sql, @JsonProperty("metric_id") String metricId,
                  Table table, List<Trace> trace) {}

    record Table(List<String> columns, List<List<Object>> rows) {}

    record Trace(String type, String name, Object input, Object outcome, JsonNode tokens,
                 @JsonProperty("latency_ms") long latencyMs) {}
}
