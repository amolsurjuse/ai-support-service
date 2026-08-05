package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.config.LocalAiRuntimeProperties;

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
import java.util.concurrent.Semaphore;

class OllamaLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);

    private final AiSupportProperties properties;
    private final LocalAiRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Semaphore requestSlots;

    OllamaLlmClient(AiSupportProperties properties, ObjectMapper objectMapper) {
        this(properties, LocalAiRuntimeProperties.defaults(), objectMapper);
    }

    OllamaLlmClient(AiSupportProperties properties, LocalAiRuntimeProperties runtimeProperties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
        this.requestSlots = new Semaphore(Math.max(1, properties.ollamaMaxConcurrentRequests()));
    }

    @Override
    public boolean available() {
        return properties.providerEnabled()
                && properties.ollamaEnabled()
                && !isBlank(properties.ollamaBaseUrl())
                && !isBlank(properties.ollamaModel());
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            log.info("Ollama provider unavailable providerEnabled={} enabled={} baseUrlConfigured={} modelConfigured={}",
                    properties.providerEnabled(), properties.ollamaEnabled(), !isBlank(properties.ollamaBaseUrl()), !isBlank(properties.ollamaModel()));
            return LlmCompletion.disabled();
        }
        if (!requestSlots.tryAcquire()) {
            log.info("Ollama request skipped because the local model is busy maxConcurrentRequests={}",
                    properties.ollamaMaxConcurrentRequests());
            return LlmCompletion.failure("ollama", properties.ollamaModel(), "Ollama is busy");
        }

        try {
            String promptText = compactPromptText(prompt);
            String body = requestBody(promptText);
            long startedNanos = System.nanoTime();
            log.info("Ollama chat request starting model={} baseUrl={} promptChars={} requestBytes={} maxOutputTokens={} temperature={}",
                    properties.ollamaModel(), sanitizeBaseUrl(properties.ollamaBaseUrl()), promptText.length(), body.length(), maxOutputTokens(), properties.temperature());
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
                return LlmCompletion.failure("ollama", properties.ollamaModel(), "Ollama returned status " + response.statusCode());
            }

            String answer = extractOutputText(objectMapper.readTree(response.body()));
            if (isBlank(answer)) {
                log.warn("Ollama chat request completed without output text status={} latencyMs={} bodyChars={}",
                        response.statusCode(), elapsedMs(startedNanos), response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("ollama", properties.ollamaModel(), "Ollama response did not include output text");
            }
            log.info("Ollama chat request succeeded status={} latencyMs={} answerChars={}",
                    response.statusCode(), elapsedMs(startedNanos), answer.length());
            return LlmCompletion.success(answer, "ollama", properties.ollamaModel());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LlmCompletion.failure("ollama", properties.ollamaModel(), "Ollama request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("Ollama chat request exception exception={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
            return LlmCompletion.failure("ollama", properties.ollamaModel(), ex.getMessage());
        } finally {
            requestSlots.release();
        }
    }

    private String requestBody(String promptText) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.ollamaModel());
        root.put("stream", false);
        root.put("think", false);
        root.put("keep_alive", runtimeProperties.ollamaKeepAlive());

        ObjectNode options = objectMapper.createObjectNode();
        options.put("temperature", properties.temperature());
        options.put("num_predict", maxOutputTokens());
        root.set("options", options);

        ArrayNode messages = objectMapper.createArrayNode();
        // The deployed ElectraHub model already carries the full system contract in its Modelfile.
        // Sending it again on every request doubles prompt prefill time on CPU-only Ollama hosts.
        messages.add(message("user", promptText));
        root.set("messages", messages);
        return root.toString();
    }

    LlmCompletion warmup() {
        if (!available()) {
            return LlmCompletion.disabled();
        }
        if (!requestSlots.tryAcquire()) {
            return LlmCompletion.failure("ollama", properties.ollamaModel(), "Ollama is busy");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.ollamaModel());
        root.put("prompt", "");
        root.put("stream", false);
        root.put("keep_alive", runtimeProperties.ollamaKeepAlive());
        ObjectNode options = objectMapper.createObjectNode();
        options.put("num_predict", 1);
        root.set("options", options);
        long startedNanos = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.ollamaBaseUrl()) + "/api/generate"))
                    .timeout(Duration.ofMillis(runtimeProperties.warmupTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Ollama model warmup succeeded model={} latencyMs={} keepAlive={}",
                        properties.ollamaModel(), elapsedMs(startedNanos), runtimeProperties.ollamaKeepAlive());
                return LlmCompletion.success("warm", "ollama", properties.ollamaModel());
            }
            return LlmCompletion.failure("ollama", properties.ollamaModel(),
                    "Ollama warmup returned status " + response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LlmCompletion.failure("ollama", properties.ollamaModel(), "Ollama warmup interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("Ollama model warmup failed model={} latencyMs={} exception={}",
                    properties.ollamaModel(), elapsedMs(startedNanos), ex.getClass().getSimpleName());
            return LlmCompletion.failure("ollama", properties.ollamaModel(), ex.getMessage());
        } finally {
            requestSlots.release();
        }
    }

    private String compactPromptText(LlmPrompt prompt) {
        return LlmPromptFormatter.promptText(prompt);
    }

    private int maxOutputTokens() {
        return Math.max(64, Math.min(properties.maxOutputTokens(), 320));
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
        return Duration.ofMillis(Math.max(1_000, properties.ollamaTimeoutMs()));
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
