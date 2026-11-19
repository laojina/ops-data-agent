package com.opsdata.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
final class MetricDictionary {

    private final ObjectMapper mapper;

    MetricDictionary(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Optional<JsonNode> lookup(String term) throws IOException {
        // 每次从文件读取，改完口径后下一次查询就能拿到新定义。
        JsonNode metrics = mapper.readTree(Path.of("data", "metrics.json").toFile());
        if (metrics == null || !metrics.isArray()) {
            throw new IOException("指标字典应是一个 JSON 数组。");
        }
        String query = term.strip();
        for (JsonNode metric : metrics) {
            if (query.equalsIgnoreCase(metric.path("id").asText())
                    || query.equalsIgnoreCase(metric.path("name").asText())) {
                return Optional.of(metric);
            }
            for (JsonNode alias : metric.path("aliases")) {
                if (query.equalsIgnoreCase(alias.asText())) {
                    return Optional.of(metric);
                }
            }
        }
        return Optional.empty();
    }
}
