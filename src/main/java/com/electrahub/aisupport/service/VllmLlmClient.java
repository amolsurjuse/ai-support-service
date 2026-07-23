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

/** OpenAI-compatible client for a self-hosted vLLM instance. */
final class VllmLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(VllmLlmClient.class);

    private final AiSupportProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    VllmLlmClient(AiSupportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout()).build();
    }

    @Override
    public boolean available() {
        return properties.providerEnabled()
                && properties.vllmEnabled()
                && !isBlank(properties.vllmBaseUrl())
                && !isBlank(properties.vllmModel());
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            return LlmCompletion.disabled();
        }

        String promptText = LlmPromptFormatter.promptText(prompt);
        String body = requestBody(promptText);
        long startedNanos = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.vllmBaseUrl()) + "/v1/chat/completions"))
                    .timeout(timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("vLLM request failed status={} latencyMs={} bodyChars={}",
                        response.statusCode(), elapsedMs(startedNanos), response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("vllm", properties.vllmModel(), "vLLM returned status " + response.statusCode());
            }
            String answer = objectMapper.readTree(response.body())
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("")
                    .trim();
            if (answer.isBlank()) {
                return LlmCompletion.failure("vllm", properties.vllmModel(), "vLLM response did not include output text");
            }
            log.info("vLLM request succeeded model={} latencyMs={} answerChars={}",
                    properties.vllmModel(), elapsedMs(startedNanos), answer.length());
            return LlmCompletion.success(answer, "vllm", properties.vllmModel());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LlmCompletion.failure("vllm", properties.vllmModel(), "vLLM request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("vLLM request exception latencyMs={} exception={}", elapsedMs(startedNanos), ex.getClass().getSimpleName());
            return LlmCompletion.failure("vllm", properties.vllmModel(), safeError(ex));
        }
    }

    private String requestBody(String promptText) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.vllmModel());
        root.put("temperature", properties.temperature());
        root.put("max_tokens", Math.max(64, Math.min(properties.maxOutputTokens(), 320)));
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", SupportPrompt.SYSTEM_PROMPT + "\n\n" + SupportPrompt.RESPONSE_CONTRACT));
        messages.add(message("user", promptText));
        root.set("messages", messages);
        return root.toString();
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1_000, properties.vllmTimeoutMs()));
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String safeError(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
