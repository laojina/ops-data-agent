package com.opsdata.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ModelToolCallingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startTestServer() throws Exception {
        // 本地替身检查协议和回传流程，真实调用用配置好的模型接口单独验收。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                byte[] body = mapper.writeValueAsBytes(completion(requests.size() == 1));
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

    private MockMvc askEndpoint(String key) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var model = new ModelClient(mapper, key, base, "test-model");
        return MockMvcBuilders.standaloneSetup(new AskController(new AskService(mapper, model,
                new MetricDictionary(mapper)))).build();
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
            message.put("content", "分母是统计范围内的全部订单数。");
        }
        return response;
    }
}
