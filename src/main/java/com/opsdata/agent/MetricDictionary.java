package com.opsdata.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
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
        String query = term.strip();
        for (JsonNode metric : read()) {
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

    boolean mentionsMetric(String question) throws IOException {
        String text = question.toLowerCase(Locale.ROOT);
        for (JsonNode metric : read()) {
            if (containsTerm(text, metric.path("id")) || containsTerm(text, metric.path("name"))) {
                return true;
            }
            for (JsonNode alias : metric.path("aliases")) {
                if (containsTerm(text, alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsTerm(String text, JsonNode term) {
        return term.isTextual() && !term.textValue().isBlank()
                && text.contains(term.textValue().toLowerCase(Locale.ROOT));
    }

    private JsonNode read() throws IOException {
        // 改完口径后下一次查询就读取新定义。
        JsonNode metrics = mapper.readTree(Path.of("data", "metrics.json").toFile());
        if (metrics == null || !metrics.isArray()) {
            throw new IOException("指标字典应是一个 JSON 数组。");
        }
        return metrics;
    }
}
