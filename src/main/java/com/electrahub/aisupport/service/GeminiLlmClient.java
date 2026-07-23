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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Optional hosted fallback. This client is deliberately disabled unless both the provider and
 * hosted-fallback flags are enabled, and it always receives a stricter redacted prompt.
 */
final class GeminiLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    private final AiSupportProperties properties;
    private final ObjectMapper objectMapper;
    private final PiiRedactor redactor;
    private final HttpClient httpClient;

    GeminiLlmClient(AiSupportProperties properties, ObjectMapper objectMapper, PiiRedactor redactor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout()).build();
    }

    @Override
    public boolean available() {
        return properties.providerEnabled()
                && properties.geminiEnabled()
                && properties.hostedFallbackEnabled()
                && !isBlank(properties.geminiApiKey())
                && !isBlank(properties.geminiBaseUrl())
                && !isBlank(properties.geminiModel());
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            return LlmCompletion.disabled();
        }

        String promptText = redactor.redactForHostedProvider(LlmPromptFormatter.promptText(prompt));
        String body = requestBody(promptText);
        long startedNanos = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(generationUri())
                    .timeout(timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Gemini request failed status={} latencyMs={} bodyChars={}",
                        response.statusCode(), elapsedMs(startedNanos), response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("gemini", properties.geminiModel(), "Gemini returned status " + response.statusCode());
            }
            String answer = extractOutputText(objectMapper.readTree(response.body()));
            if (answer.isBlank()) {
                return LlmCompletion.failure("gemini", properties.geminiModel(), "Gemini response did not include output text");
            }
            log.info("Gemini request succeeded model={} latencyMs={} answerChars={}",
                    properties.geminiModel(), elapsedMs(startedNanos), answer.length());
            return LlmCompletion.success(answer, "gemini", properties.geminiModel());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LlmCompletion.failure("gemini", properties.geminiModel(), "Gemini request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("Gemini request exception latencyMs={} exception={}", elapsedMs(startedNanos), ex.getClass().getSimpleName());
            return LlmCompletion.failure("gemini", properties.geminiModel(), safeError(ex));
        }
    }

    private URI generationUri() {
        String model = URLEncoder.encode(properties.geminiModel(), StandardCharsets.UTF_8);
        String key = URLEncoder.encode(properties.geminiApiKey(), StandardCharsets.UTF_8);
        return URI.create(trimTrailingSlash(properties.geminiBaseUrl())
                + "/v1beta/models/" + model + ":generateContent?key=" + key);
    }

    private String requestBody(String promptText) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode systemInstruction = objectMapper.createObjectNode();
        ArrayNode systemParts = objectMapper.createArrayNode();
        systemParts.add(textPart(SupportPrompt.SYSTEM_PROMPT + "\n\n" + SupportPrompt.RESPONSE_CONTRACT));
        systemInstruction.set("parts", systemParts);
        root.set("systemInstruction", systemInstruction);

        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        ArrayNode userParts = objectMapper.createArrayNode();
        userParts.add(textPart(promptText));
        user.set("parts", userParts);
        contents.add(user);
        root.set("contents", contents);

        ObjectNode generation = objectMapper.createObjectNode();
        generation.put("temperature", properties.temperature());
        generation.put("maxOutputTokens", Math.max(64, Math.min(properties.maxOutputTokens(), 320)));
        root.set("generationConfig", generation);
        return root.toString();
    }

    private ObjectNode textPart(String text) {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("text", text);
        return part;
    }

    private String extractOutputText(JsonNode response) {
        StringBuilder answer = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                if (!answer.isEmpty()) {
                    answer.append('\n');
                }
                answer.append(text);
            }
        }
        return answer.toString().trim();
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1_000, properties.geminiTimeoutMs()));
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String safeError(Exception ex) {
        // Gemini authenticates with a key in the request query string. Do not surface an exception
        // message because an invalid URI can echo that key into the application log.
        return "Gemini request failed: " + ex.getClass().getSimpleName();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
