package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class OllamaLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);

    private final AiSupportProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    OllamaLlmClient(AiSupportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    @Override
    public boolean available() {
        return properties.providerEnabled()
                && "ollama".equalsIgnoreCase(properties.provider())
                && !isBlank(properties.ollamaBaseUrl())
                && !isBlank(properties.model());
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            log.info("Ollama provider unavailable providerEnabled={} provider={} baseUrlConfigured={} modelConfigured={}",
                    properties.providerEnabled(), properties.provider(), !isBlank(properties.ollamaBaseUrl()), !isBlank(properties.model()));
            return LlmCompletion.disabled();
        }

        String body = requestBody(prompt);
        long startedNanos = System.nanoTime();
        log.info("Ollama chat request starting model={} baseUrl={} promptChars={} requestBytes={} maxOutputTokens={} temperature={}",
                properties.model(), sanitizeBaseUrl(properties.ollamaBaseUrl()), LlmPromptFormatter.promptSize(prompt), body.length(), properties.maxOutputTokens(), properties.temperature());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.ollamaBaseUrl()) + "/api/chat"))
                    .timeout(timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Ollama chat request failed status={} latencyMs={} bodyChars={}",
                        response.statusCode(), elapsedMs(startedNanos), response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("ollama", properties.model(), "Ollama returned status " + response.statusCode());
            }

            String answer = extractOutputText(objectMapper.readTree(response.body()));
            if (isBlank(answer)) {
                log.warn("Ollama chat request completed without output text status={} latencyMs={} bodyChars={}",
                        response.statusCode(), elapsedMs(startedNanos), response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("ollama", properties.model(), "Ollama response did not include output text");
            }
            log.info("Ollama chat request succeeded status={} latencyMs={} answerChars={}",
                    response.statusCode(), elapsedMs(startedNanos), answer.length());
            return LlmCompletion.success(answer, "ollama", properties.model());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Ollama chat request interrupted latencyMs={}", elapsedMs(startedNanos));
            return LlmCompletion.failure("ollama", properties.model(), "Ollama request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("Ollama chat request exception latencyMs={} exception={} message={}",
                    elapsedMs(startedNanos), ex.getClass().getSimpleName(), ex.getMessage());
            return LlmCompletion.failure("ollama", properties.model(), ex.getMessage());
        }
    }

    private String requestBody(LlmPrompt prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.model());
        root.put("stream", false);

        ObjectNode options = objectMapper.createObjectNode();
        options.put("temperature", properties.temperature());
        options.put("num_predict", properties.maxOutputTokens());
        root.set("options", options);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", SupportPrompt.SYSTEM_PROMPT + "\n\n" + SupportPrompt.RESPONSE_CONTRACT));
        messages.add(message("user", LlmPromptFormatter.promptText(prompt)));
        root.set("messages", messages);
        return root.toString();
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private String extractOutputText(JsonNode json) {
        String chatMessage = json.path("message").path("content").asText("");
        if (!isBlank(chatMessage)) {
            return chatMessage;
        }
        return json.path("response").asText("");
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1_000, properties.llmTimeoutMs()));
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String sanitizeBaseUrl(String value) {
        return isBlank(value) ? "-" : value.replaceAll("(?i)(api[_-]?key|token|secret)=([^&]+)", "$1=REDACTED");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
