package com.opsdata.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ModelToolCallingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile IntFunction<JsonNode> response = round -> completion(round == 1);

    @TempDir
    Path directory;

    @BeforeEach
    void startTestServer() throws Exception {
        // 本地替身检查协议和回传流程，真实调用用配置好的模型接口单独验收。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                byte[] body = mapper.writeValueAsBytes(response.apply(requests.size()));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopTestServer() {
        server.stop(0);
    }

    @Test
    void passesMetricDefinitionBackToTheModelWithTheOriginalToolCallId() throws Exception {
        askEndpoint("test-credential").perform(post("/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"test","question":"异常率的分母是什么？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.metric_id").value("M02"))
                .andExpect(jsonPath("$.trace[0].tokens.total_tokens").value(111))
                .andExpect(jsonPath("$.trace[1].outcome.metric.id").value("M02"))
                .andExpect(jsonPath("$.trace[2].tokens.total_tokens").value(159));

        assertEquals(2, requests.size());
        assertEquals("lookup_metric", requests.get(0).at("/tools/0/function/name").asText());
        JsonNode followupMessages = requests.get(1).path("messages");
        assertEquals("assistant", followupMessages.path(2).path("role").asText());
        JsonNode toolResult = followupMessages.path(3);
        assertEquals("call-test-1", toolResult.path("tool_call_id").asText());
        assertEquals("M02", mapper.readTree(toolResult.path("content").asText()).at("/metric/id").asText());
        System.out.println("PASS | Model returned a tool call, Java found M02, and the same call ID carried it back.");
    }

    @Test
    void rejectsBadInputAndDoesNotCallTheModelWithoutAKey() throws Exception {
        MockMvc endpoint = askEndpoint("");
        endpoint.perform(post("/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"test\",\"question\":42}"))
                .andExpect(status().isBadRequest());
        endpoint.perform(post("/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"test\",\"question\":\"运输异常率怎么算？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.trace[0].tokens").doesNotExist());
        assertTrue(requests.isEmpty());
        System.out.println("PASS | Invalid input returns HTTP 400. Missing key gives an actionable error.");
    }

    @Test
    void passesSqliteErrorBackAndDisplaysOnlyTheActualQueryValue() throws Exception {
        SeedDatabase.create(Path.of("seed.sql"), directory.resolve("ops.db"));
        response = this::queryCompletion;
        askEndpoint("test-credential").perform(post("/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"test","question":"出库票量是多少？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.metric_id").value("M05"))
                .andExpect(jsonPath("$.table.rows[0][0]").value(2))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("999999"))));

        assertEquals(4, requests.size());
        JsonNode repairMessage = requests.get(2).path("messages").path(6);
        assertEquals("bad-query", repairMessage.path("tool_call_id").asText());
        assertTrue(mapper.readTree(repairMessage.path("content").asText())
                .path("error").asText().contains("no such column"));
        System.out.println("PASS | SQLite error returned to the model. The rewritten SQL result became the answer.");
    }

    @Test
    void keepsSixTurnsAndSeparatesSessions() throws Exception {
        response = round -> {
            ObjectNode result = (ObjectNode) completion(false);
            ((ObjectNode) result.at("/choices/0/message")).put("content", round == 1
                    ? "NEED_CLARIFY: 2026 年 10 月想看及时率、票量还是异常率？"
                    : "INSUFFICIENT_EVIDENCE: 暂无查询结果。");
            return result;
        };
        MockMvc endpoint = askEndpoint("test-credential");
        for (int i = 0; i < 8; i++) {
            endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"session_id\":\"first\",\"question\":\"question-" + i + "\"}"))
                    .andExpect(status().isOk());
        }
        JsonNode afterClarification = requests.get(1).path("messages");
        assertTrue(afterClarification.toString().contains("2026 年 10 月想看及时率"));
        assertEquals(14, requests.get(7).path("messages").size());
        assertTrue(!requests.get(7).path("messages").toString().contains("question-0"));
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"another\",\"question\":\"different session\"}"))
                .andExpect(status().isOk());
        assertEquals(2, requests.get(8).path("messages").size());
        System.out.println("PASS | Clarification context is retained, six turns are kept, and sessions are isolated.");
    }

    @Test
    void guardClarifiesVagueShipmentAndStopsQueriesBeyondTheDataWindow() throws Exception {
        response = round -> completion(false);
        MockMvc endpoint = askEndpoint("test-credential");
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"scope\",\"question\":\"上个月出库怎么样？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("need_clarify"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2026 年 10 月")))
                .andExpect(jsonPath("$.sql.length()").value(0));
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"dates\",\"question\":\"11 月的及时率呢？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("10 月 31 日")))
                .andExpect(jsonPath("$.sql.length()").value(0));
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"phone\",\"question\":\"把客户的手机号导出来\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("没有客户手机号字段")))
                .andExpect(jsonPath("$.sql.length()").value(0));
        assertEquals(3, requests.size());
        System.out.println("PASS | Vague shipment, unavailable dates and missing sensitive fields stay outside SQL.");
    }

    @Test
    void refusesOutOfWindowDaysInCommonDateFormatsBeforeRunningSql() throws Exception {
        SeedDatabase.create(Path.of("seed.sql"), directory.resolve("ops.db"));
        response = this::queryCompletion;
        MockMvc endpoint = askEndpoint("test-credential");
        for (String date : List.of("11/2", "2026-11-02", "2026/11/02", "2026年11月2日", "11月2号")) {
            requests.clear();
            endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.createObjectNode().put("session_id", date)
                                    .put("question", "查 " + date + " 的履约达成率").toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                    .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2026 年 11 月 2 日")))
                    .andExpect(jsonPath("$.sql.length()").value(0));
            assertEquals(1, requests.size());
        }
        for (String date : List.of("10/31", "2026-10-31")) {
            requests.clear();
            endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.createObjectNode().put("session_id", date)
                                    .put("question", date + " 的出库票量是多少？").toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("answered"));
            assertTrue(requests.get(0).at("/messages/0/content").asText().contains("2026 年 10 月 31 日"));
        }
    }

    @Test
    void reportsOnlyTheUncoveredPartOfAComparisonOrRange() throws Exception {
        response = round -> completion(false);
        MockMvc endpoint = askEndpoint("test-credential");
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"compare","question":"对比 9 月和 11 月的履约达成率"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2026 年 11 月没有可用数据")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2026 年 9 月在样例库覆盖范围内")))
                .andExpect(jsonPath("$.sql.length()").value(0));
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"range","question":"查询 2026-10-30 至 2026-11-02 的履约达成率"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2026 年 11 月 1 日 至 2026 年 11 月 2 日没有可用数据")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2026 年 10 月 30 日 至 2026 年 10 月 31 日在样例库覆盖范围内")))
                .andExpect(jsonPath("$.sql.length()").value(0));
    }

    private JsonNode queryCompletion(int round) {
        ObjectNode result = (ObjectNode) completion(false);
        ObjectNode message = (ObjectNode) result.at("/choices/0/message");
        if (round == 4) {
            message.put("content", "统计得到 999999 条记录。");
            return result;
        }
        message.putNull("content");
        var calls = message.putArray("tool_calls");
        switch (round) {
            case 1 -> {
                addCall(calls, "metric-id", "lookup_metric", "{\"term\":\"出库票量\"}");
                addCall(calls, "schema-id", "describe_table", "{\"name\":\"orders\"}");
            }
            case 2 -> addCall(calls, "bad-query", "run_sql",
                    "{\"sql\":\"SELECT missing_column FROM orders LIMIT 1\"}");
            case 3 -> addCall(calls, "fixed-query", "run_sql",
                    "{\"sql\":\"SELECT count(*) AS order_count FROM orders WHERE order_id LIKE 'FIX-SEP-%' AND shipped_at IS NOT NULL LIMIT 1\"}");
            default -> throw new IllegalArgumentException("Unexpected test round " + round);
        }
        return result;
    }

    private void addCall(com.fasterxml.jackson.databind.node.ArrayNode calls, String id, String name, String arguments) {
        calls.addObject().put("id", id).put("type", "function").putObject("function")
                .put("name", name).put("arguments", arguments);
    }

    @Test
    void countsOnlyPermissionBlocksMetricSqlWithEitherToolDescription() throws Exception {
        response = round -> round < 3 ? queryCompletion(round) : completion(false);
        for (String description : List.of("仅用于统计表行数", "只允许统计表的总行数。不得查询经营指标。")) {
            requests.clear();
            askEndpoint("test-credential", description, true).perform(post("/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"session_id\":\"scope\",\"question\":\"出库票量是多少？\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                    .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("仅支持统计表行数")))
                    .andExpect(jsonPath("$.sql.length()").value(0))
                    .andExpect(jsonPath("$.trace[4].outcome.error").value(org.hamcrest.Matchers.containsString("仅用于统计表行数")));
            JsonNode tool = requests.get(0).at("/tools/3/function");
            assertEquals(description, tool.path("description").asText());
            assertTrue(tool.at("/parameters/properties/sql").isMissingNode());
        }
    }

    @Test
    void countsOnlyModeAnswersWholeTableCountFromListTables() throws Exception {
        SeedDatabase.create(Path.of("seed.sql"), directory.resolve("ops.db"));
        response = round -> {
            JsonNode result = completion(false);
            ObjectNode message = (ObjectNode) result.at("/choices/0/message");
            if (round == 1) {
                message.putNull("content");
                addCall(message.putArray("tool_calls"), "table-count", "list_tables", "{}");
            } else {
                message.put("content", "INSUFFICIENT_EVIDENCE: 无法查询这个指标。");
            }
            return result;
        };
        MockMvc endpoint = askEndpoint("test-credential", "仅用于统计表行数", true);
        endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"table-count","question":"`orders` 表有多少行？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("1203")))
                .andExpect(jsonPath("$.table.rows[0][0]").value("orders"))
                .andExpect(jsonPath("$.table.rows[0][1]").value(1203))
                .andExpect(jsonPath("$.sql.length()").value(0))
                .andExpect(jsonPath("$.trace[1].outcome.tables[1].rows").value(1203));

        for (String question : List.of("10 月华东区出库及时率是多少？", "orders 表里 10 月份的订单有多少行？")) {
            requests.clear();
            endpoint.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.createObjectNode().put("session_id", question)
                                    .put("question", question).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                    .andExpect(jsonPath("$.table.rows.length()").value(0))
                    .andExpect(jsonPath("$.sql.length()").value(0));
        }
    }

    @Test
    void changingOnlyTheDescriptionDoesNotChangeExecutionPermissions() throws Exception {
        SeedDatabase.create(Path.of("seed.sql"), directory.resolve("ops.db"));
        response = this::queryCompletion;
        askEndpoint("test-credential", "仅用于统计表行数").perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"description","question":"出库票量是多少？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.table.rows[0][0]").value(2));
        assertEquals("仅用于统计表行数", requests.get(0).at("/tools/3/function/description").asText());
        assertTrue(requests.get(0).at("/messages/0/content").asText().contains("本轮 run_sql 的工具描述是：「仅用于统计表行数」"));
    }

    private MockMvc askEndpoint(String key) {
        return askEndpoint(key, "执行带 LIMIT 的只读查询。");
    }

    private MockMvc askEndpoint(String key, String description) {
        return askEndpoint(key, description, false);
    }

    private MockMvc askEndpoint(String key, String description, boolean tableCountsOnly) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var model = new ModelClient(mapper, key, base, "test-model");
        return MockMvcBuilders.standaloneSetup(new AskController(new AskService(mapper, model,
                new MetricDictionary(mapper), new ReadOnlyDatabase(directory.resolve("ops.db").toString()),
                description, tableCountsOnly, "2026-11-19"))).build();
    }

    private JsonNode completion(boolean first) {
        ObjectNode response = mapper.createObjectNode().put("model", "test-model");
        response.putObject("usage").put("prompt_tokens", first ? 100 : 140)
                .put("completion_tokens", first ? 11 : 19).put("total_tokens", first ? 111 : 159);
        ObjectNode message = response.putArray("choices").addObject().putObject("message")
                .put("role", "assistant");
        if (first) {
            message.putNull("content");
            message.putArray("tool_calls").addObject().put("id", "call-test-1").put("type", "function")
                    .putObject("function").put("name", "lookup_metric")
                    .put("arguments", "{\"term\":\"异常率\"}");
        } else {
            message.put("content", "METRIC_DEFINITION: 分母是统计范围内的全部订单数。");
        }
        return response;
    }
}
