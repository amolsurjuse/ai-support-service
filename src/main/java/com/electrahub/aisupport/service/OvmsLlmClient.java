package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.config.LocalAiRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI-compatible client for Intel OpenVINO Model Server. */
final class OvmsLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OvmsLlmClient.class);

    private final AiSupportProperties properties;
    private final LocalAiRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    OvmsLlmClient(AiSupportProperties properties, LocalAiRuntimeProperties runtimeProperties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout()).build();
    }

    @Override
    public boolean available() {
        return properties.providerEnabled()
                && runtimeProperties.ovmsEnabled()
                && !runtimeProperties.ovmsBaseUrl().isBlank()
                && !runtimeProperties.ovmsModel().isBlank();
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            return LlmCompletion.disabled();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", runtimeProperties.ovmsModel());
        root.put("stream", false);
        root.put("temperature", properties.temperature());
        root.put("max_tokens", Math.max(64, Math.min(properties.maxOutputTokens(), 320)));
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", SupportPrompt.SYSTEM_PROMPT + "\n\n" + SupportPrompt.RESPONSE_CONTRACT));
        messages.add(message("user", LlmPromptFormatter.promptText(prompt)));
        root.set("messages", messages);

        long startedNanos = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(runtimeProperties.ovmsBaseUrl()) + "/v3/chat/completions"))
                    .timeout(timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return LlmCompletion.failure("ovms", runtimeProperties.ovmsModel(),
                        "OVMS returned status " + response.statusCode());
            }
            String answer = objectMapper.readTree(response.body()).path("choices").path(0)
                    .path("message").path("content").asText("").trim();
            if (answer.isBlank()) {
                return LlmCompletion.failure("ovms", runtimeProperties.ovmsModel(),
                        "OVMS response did not include output text");
            }
            log.info("OVMS request succeeded model={} latencyMs={} answerChars={}",
                    runtimeProperties.ovmsModel(), elapsedMs(startedNanos), answer.length());
            return LlmCompletion.success(answer, "ovms", runtimeProperties.ovmsModel());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LlmCompletion.failure("ovms", runtimeProperties.ovmsModel(), "OVMS request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("OVMS request failed model={} latencyMs={} exception={}",
                    runtimeProperties.ovmsModel(), elapsedMs(startedNanos), ex.getClass().getSimpleName());
            return LlmCompletion.failure("ovms", runtimeProperties.ovmsModel(), safeError(ex));
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private Duration timeout() {
        return Duration.ofMillis(runtimeProperties.ovmsTimeoutMs());
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String safeError(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
