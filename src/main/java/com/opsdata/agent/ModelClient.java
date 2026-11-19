package com.opsdata.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ModelClient {

    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    ModelClient(ObjectMapper mapper, @Value("${llm.api-key}") String apiKey,
                @Value("${llm.base-url}") String baseUrl, @Value("${llm.model}") String model) {
        this.mapper = mapper;
        this.apiKey = apiKey.strip();
        this.model = model.strip();
        this.endpoint = URI.create(baseUrl.strip().replaceAll("/+$", "") + "/chat/completions");
    }

    String model() {
        return model;
    }

    Completion complete(ArrayNode messages, ArrayNode tools) throws IOException, InterruptedException {
        if (apiKey.isBlank()) {
            throw new IOException("缺少 LLM_API_KEY。请配置模型凭据后重启服务。");
        }

        ObjectNode payload = mapper.createObjectNode().put("model", model).put("max_tokens", 1024).put("temperature", 0.0);
        payload.set("messages", messages);
        payload.set("tools", tools);
        if ("api.deepseek.com".equalsIgnoreCase(endpoint.getHost())) {
            payload.putObject("thinking").put("type", "disabled");
        }

        // 凭据只放在 HTTP Authorization 请求头里，调用记录从响应中提取。
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)))
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new IOException("模型地址或凭据格式无效，请检查本机配置。");
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IOException("连接模型接口失败或超时，请检查接口地址和网络。");
        }
        if (response.statusCode() != 200) {
            throw new IOException("模型接口返回 HTTP " + response.statusCode()
                    + "。请核对接口地址、凭据和模型名。");
        }

        JsonNode result;
        try {
            result = mapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new IOException("模型接口返回的内容无法解析。");
        }
        if (result == null) {
            throw new IOException("模型接口返回空响应。");
        }
        JsonNode message = result.path("choices").path(0).path("message");
        JsonNode usage = result.path("usage");
        String actualModel = result.path("model").asText("");
        if (!message.isObject() || actualModel.isBlank() || !usage.path("total_tokens").isIntegralNumber()) {
            throw new IOException("模型响应缺少消息、模型名或 token 用量。");
        }
        return new Completion(message, actualModel, usage);
    }

    record Completion(JsonNode message, String model, JsonNode usage) {}
}
