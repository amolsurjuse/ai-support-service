package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.config.LocalAiRuntimeProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class RoutingLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(RoutingLlmClient.class);

    private final AiSupportProperties properties;
    private final OpenAiLlmClient openAi;
    private final OllamaLlmClient ollama;
    private final VllmLlmClient vllm;
    private final OvmsLlmClient ovms;
    private final GeminiLlmClient gemini;
    private final LlmProviderCircuitBreaker circuitBreaker;

    RoutingLlmClient(AiSupportProperties properties, ObjectMapper objectMapper, PiiRedactor redactor) {
        this(properties, LocalAiRuntimeProperties.defaults(), objectMapper, redactor);
    }

    @Autowired
    RoutingLlmClient(AiSupportProperties properties, LocalAiRuntimeProperties runtimeProperties,
                     ObjectMapper objectMapper, PiiRedactor redactor) {
        this.properties = properties;
        this.openAi = new OpenAiLlmClient(properties, objectMapper);
        this.ollama = new OllamaLlmClient(properties, runtimeProperties, objectMapper);
        this.vllm = new VllmLlmClient(properties, objectMapper);
        this.ovms = new OvmsLlmClient(properties, runtimeProperties, objectMapper);
        this.gemini = new GeminiLlmClient(properties, objectMapper, redactor);
        this.circuitBreaker = new LlmProviderCircuitBreaker(properties.providerFailureCooldownMs());
    }

    @Override
    public boolean available() {
        return orderedClients().stream()
                .anyMatch(candidate -> candidate.client().available() && circuitBreaker.allows(candidate.name()));
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        LlmCompletion lastFailure = LlmCompletion.disabled();
        boolean candidateWasConfigured = false;
        for (NamedClient candidate : orderedClients()) {
            if (!candidate.client().available()) {
                continue;
            }
            candidateWasConfigured = true;
            if (!circuitBreaker.allows(candidate.name())) {
                log.info("LLM provider skipped during cooldown provider={}", candidate.name());
                continue;
            }

            LlmCompletion completion = candidate.client().complete(prompt);
            if (completion.ok() && !completion.answer().isBlank()) {
                circuitBreaker.recordSuccess(candidate.name());
                return completion;
            }

            lastFailure = completion;
            if (completion.error() != null && completion.error().toLowerCase(Locale.ROOT).contains("busy")) {
                log.info("LLM provider busy provider={} moving to next configured provider", candidate.name());
                continue;
            }
            circuitBreaker.recordFailure(candidate.name());
            log.warn("LLM provider failed provider={} model={} error={} cooldownMs={}",
                    candidate.name(), completion.model(), completion.error(), properties.providerFailureCooldownMs());
        }
        if (!candidateWasConfigured) {
            log.info("LLM provider disabled or unavailable providerChain={}", properties.providerOrder());
        }
        return lastFailure;
    }

    LlmCompletion warmupOllama() {
        return ollama.warmup();
    }

    private List<NamedClient> orderedClients() {
        Map<String, LlmClient> clients = Map.of(
                "vllm", vllm,
                "ovms", ovms,
                "ollama", ollama,
                "gemini", gemini,
                "openai", openAi
        );
        List<NamedClient> local = new ArrayList<>();
        List<NamedClient> hosted = new ArrayList<>();
        for (String provider : properties.providerOrder()) {
            LlmClient client = clients.get(provider);
            if (client == null) {
                log.warn("Ignoring unsupported AI provider in chain provider={}", provider);
                continue;
            }
            NamedClient named = new NamedClient(provider, client);
            if ("gemini".equals(provider) || "openai".equals(provider)) {
                hosted.add(named);
            } else {
                local.add(named);
            }
        }
        local.addAll(hosted);
        return local;
    }

    private record NamedClient(String name, LlmClient client) {
    }
}
