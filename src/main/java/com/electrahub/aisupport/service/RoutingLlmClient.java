package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
class RoutingLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(RoutingLlmClient.class);

    private final AiSupportProperties properties;
    private final OpenAiLlmClient openAi;
    private final OllamaLlmClient ollama;

    RoutingLlmClient(AiSupportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.openAi = new OpenAiLlmClient(properties, objectMapper);
        this.ollama = new OllamaLlmClient(properties, objectMapper);
    }

    @Override
    public boolean available() {
        return selected().available();
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        LlmClient selected = selected();
        if (!selected.available()) {
            log.info("LLM provider disabled or unavailable provider={}", properties.provider());
            return LlmCompletion.disabled();
        }
        return selected.complete(prompt);
    }

    private LlmClient selected() {
        if ("ollama".equalsIgnoreCase(properties.provider())) {
            return ollama;
        }
        return openAi;
    }
}
