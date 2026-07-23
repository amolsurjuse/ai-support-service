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

class OpenAiLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final AiSupportProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    OpenAiLlmClient(AiSupportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    @Override
    public boolean available() {
        return properties.providerEnabled()
                && !isBlank(properties.openaiApiKey());
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            log.info("OpenAI provider unavailable providerEnabled={} hasApiKey={}",
                    properties.providerEnabled(), !isBlank(properties.openaiApiKey()));
            return LlmCompletion.disabled();
        }

        String body = requestBody(prompt);
        long startedNanos = System.nanoTime();
        log.info("OpenAI response request starting provider=openai model={} baseUrl={} promptChars={} requestBytes={} maxOutputTokens={} temperature={}",
                properties.model(), sanitizeBaseUrl(properties.openaiBaseUrl()), LlmPromptFormatter.promptSize(prompt), body.length(), properties.maxOutputTokens(), properties.temperature());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.openaiBaseUrl()) + "/v1/responses"))
                    .timeout(timeout())
                    .header("Authorization", "Bearer " + properties.openaiApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = elapsedMs(startedNanos);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OpenAI response request failed status={} latencyMs={} retryAfter={} rateLimitRequests={} rateLimitTokens={} resetRequests={} resetTokens={} errorCode={} errorType={} bodyChars={}",
                        response.statusCode(),
                        latencyMs,
                        header(response, "retry-after"),
                        header(response, "x-ratelimit-remaining-requests"),
                        header(response, "x-ratelimit-remaining-tokens"),
                        header(response, "x-ratelimit-reset-requests"),
                        header(response, "x-ratelimit-reset-tokens"),
                        errorField(response.body(), "code"),
                        errorField(response.body(), "type"),
                        response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("openai", properties.model(), "OpenAI returned status " + response.statusCode());
            }

            String answer = extractOutputText(objectMapper.readTree(response.body()));
            if (isBlank(answer)) {
                log.warn("OpenAI response request completed without output text status={} latencyMs={} bodyChars={}",
                        response.statusCode(), elapsedMs(startedNanos), response.body() == null ? 0 : response.body().length());
                return LlmCompletion.failure("openai", properties.model(), "OpenAI response did not include output text");
            }
            log.info("OpenAI response request succeeded status={} latencyMs={} answerChars={} outputItems={}",
                    response.statusCode(), elapsedMs(startedNanos), answer.length(), responseBodyOutputCount(response.body()));
            return LlmCompletion.success(answer, "openai", properties.model());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("OpenAI response request interrupted latencyMs={}", elapsedMs(startedNanos));
            return LlmCompletion.failure("openai", properties.model(), "OpenAI request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("OpenAI response request exception latencyMs={} exception={} message={}",
                    elapsedMs(startedNanos), ex.getClass().getSimpleName(), ex.getMessage());
            return LlmCompletion.failure("openai", properties.model(), ex.getMessage());
        }
    }

    private String requestBody(LlmPrompt prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.model());
        root.put("instructions", SupportPrompt.SYSTEM_PROMPT + "\n\n" + SupportPrompt.RESPONSE_CONTRACT);
        root.put("temperature", properties.temperature());
        root.put("max_output_tokens", properties.maxOutputTokens());

        ArrayNode input = objectMapper.createArrayNode();
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "input_text");
        text.put("text", LlmPromptFormatter.promptText(prompt));
        content.add(text);
        user.set("content", content);
        input.add(user);
        root.set("input", input);
        return root.toString();
    }

    private String extractOutputText(JsonNode json) {
        String direct = json.path("output_text").asText("");
        if (!isBlank(direct)) {
            return direct;
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode output : json.path("output")) {
            for (JsonNode content : output.path("content")) {
                String text = content.path("text").asText("");
                if (!isBlank(text)) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
        }
        return builder.toString().trim();
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("-");
    }

    private String errorField(String body, String fieldName) {
        if (isBlank(body)) {
            return "-";
        }
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String value = error.path(fieldName).asText("");
            return isBlank(value) ? "-" : value;
        } catch (RuntimeException ex) {
            return "unparseable";
        }
    }

    private int responseBodyOutputCount(String body) {
        if (isBlank(body)) {
            return 0;
        }
        try {
            return objectMapper.readTree(body).path("output").size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String sanitizeBaseUrl(String value) {
        if (isBlank(value)) {
            return "https://api.openai.com";
        }
        return value.replaceAll("(?i)(api[_-]?key|token|secret)=([^&]+)", "$1=REDACTED");
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1_000, properties.llmTimeoutMs()));
    }

    private static String trimTrailingSlash(String value) {
        if (isBlank(value)) {
            return "https://api.openai.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
