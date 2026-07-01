package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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

@Service
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
                && "openai".equalsIgnoreCase(properties.provider())
                && !isBlank(properties.openaiApiKey());
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        if (!available()) {
            return LlmCompletion.disabled();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.openaiBaseUrl()) + "/v1/responses"))
                    .timeout(timeout())
                    .header("Authorization", "Bearer " + properties.openaiApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OpenAI response request failed with status {}", response.statusCode());
                return LlmCompletion.failure("openai", properties.model(), "OpenAI returned status " + response.statusCode());
            }

            String answer = extractOutputText(objectMapper.readTree(response.body()));
            if (isBlank(answer)) {
                return LlmCompletion.failure("openai", properties.model(), "OpenAI response did not include output text");
            }
            return LlmCompletion.success(answer, "openai", properties.model());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LlmCompletion.failure("openai", properties.model(), "OpenAI request interrupted");
        } catch (IOException | RuntimeException ex) {
            log.warn("OpenAI response request failed: {}", ex.getMessage());
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
        text.put("text", promptText(prompt));
        content.add(text);
        user.set("content", content);
        input.add(user);
        root.set("input", input);
        return root.toString();
    }

    private String promptText(LlmPrompt prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("User message:\n").append(nullToBlank(prompt.userMessage())).append("\n\n");
        builder.append("Audience and screen context:\n");
        if (prompt.context() == null) {
            builder.append("- none\n");
        } else {
            append(builder, "audience", prompt.context().audience());
            append(builder, "screen", prompt.context().screen());
            append(builder, "resourceType", prompt.context().resourceType());
            append(builder, "chargerId", prompt.context().chargerId());
            append(builder, "connectorId", prompt.context().connectorId());
            append(builder, "locationId", prompt.context().locationId());
            append(builder, "sessionId", prompt.context().sessionId());
        }
        builder.append("\nDeterministic Sparky fallback answer. Preserve its safety and do not contradict live facts:\n")
                .append(prompt.deterministicAnswer() == null ? "" : prompt.deterministicAnswer().text())
                .append("\n\nLive backend facts and gaps:\n")
                .append(prompt.diagnostics() == null ? "No backend facts were available." : prompt.diagnostics().toAnswerText())
                .append("\n\nWrite the final user-facing answer now.");
        return builder.toString();
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

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1_000, properties.llmTimeoutMs()));
    }

    private static String trimTrailingSlash(String value) {
        if (isBlank(value)) {
            return "https://api.openai.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (!isBlank(value)) {
            builder.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
