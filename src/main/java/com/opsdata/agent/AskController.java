package com.opsdata.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
final class AskController {

    private final AskService service;

    AskController(AskService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    AskService.Answer ask(@RequestBody JsonNode request) {
        JsonNode sessionId = request.path("session_id");
        JsonNode question = request.path("question");
        if (!sessionId.isTextual() || sessionId.textValue().isBlank()
                || !question.isTextual() || question.textValue().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session_id 和 question 必须是非空字符串。");
        }
        return service.ask(sessionId.textValue().strip(), question.textValue().strip());
    }
}
