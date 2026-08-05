package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.config.LocalAiRuntimeProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderLlmClientTest {

    @Test
    void callsOvmsV3OpenAiCompatibleEndpoint() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v3/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"The charger is available for a new session."}}]}
                    """);
        });
        server.start();
        try {
            LocalAiRuntimeProperties runtime = new LocalAiRuntimeProperties(
                    "4h", true, 30_000, true, url(server),
                    "OpenVINO/Qwen3-8B-int4-ov", 2_000);
            OvmsLlmClient client = new OvmsLlmClient(
                    properties("ovms", false, url(server), false, false, false, ""),
                    runtime,
                    new ObjectMapper());

            LlmClient.LlmCompletion completion = client.complete(prompt());

            assertThat(completion.ok()).isTrue();
            assertThat(completion.provider()).isEqualTo("ovms");
            assertThat(completion.model()).isEqualTo("OpenVINO/Qwen3-8B-int4-ov");
            assertThat(requestBody.get()).contains("\"model\":\"OpenVINO/Qwen3-8B-int4-ov\"");
            assertThat(requestBody.get()).contains("\"stream\":false");
            assertThat(requestBody.get()).contains("\"role\":\"system\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callsVllmOpenAiCompatibleEndpoint() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"The connector is busy. Choose another available connector."}}]}
                    """);
        });
        server.start();
        try {
            VllmLlmClient client = new VllmLlmClient(properties(
                    "vllm", true, url(server), true, false, false, ""), new ObjectMapper());

            LlmClient.LlmCompletion completion = client.complete(prompt());

            assertThat(completion.ok()).isTrue();
            assertThat(completion.provider()).isEqualTo("vllm");
            assertThat(completion.model()).isEqualTo("sparky-qwen3-8b");
            assertThat(requestBody.get()).contains("\"model\":\"sparky-qwen3-8b\"");
            assertThat(requestBody.get()).contains("\"role\":\"system\"");
            assertThat(requestBody.get()).contains("\"role\":\"user\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redactsSensitiveValuesBeforeCallingGemini() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash-lite:generateContent", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"candidates":[{"content":{"parts":[{"text":"The selected charger is charging. Choose another available connector."}]}}]}
                    """);
        });
        server.start();
        try {
            GeminiLlmClient client = new GeminiLlmClient(
                    properties("gemini", false, url(server), false, true, true, "free-test-key"),
                    new ObjectMapper(),
                    new PiiRedactor());
            LlmClient.LlmPrompt prompt = new LlmClient.LlmPrompt(
                    "Can you help jane@example.com with card 4111 1111 1111 1111 and token=top-secret?",
                    new ContextPayload("map", "charger", null, "EH-US-0001", "CON-US-0001", "LOC-1",
                            "123e4567-e89b-12d3-a456-426614174000", "driver"),
                    new DiagnosticAnswerService.DiagnosticAnswer("check_charger_availability", "Fallback answer", "charger: EH-US-0001"),
                    new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())
            );

            LlmClient.LlmCompletion completion = client.complete(prompt);

            assertThat(completion.ok()).isTrue();
            assertThat(completion.provider()).isEqualTo("gemini");
            assertThat(requestBody.get()).contains("[email-redacted]");
            assertThat(requestBody.get()).contains("[payment-redacted]");
            assertThat(requestBody.get()).contains("[id-redacted]");
            assertThat(requestBody.get()).contains("token=[secret-redacted]");
            assertThat(requestBody.get()).doesNotContain("jane@example.com");
            assertThat(requestBody.get()).doesNotContain("4111 1111 1111 1111");
            assertThat(requestBody.get()).doesNotContain("123e4567-e89b-12d3-a456-426614174000");
            assertThat(requestBody.get()).doesNotContain("top-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackFromFailedVllmAndSkipsItDuringCooldown() throws IOException {
        AtomicInteger vllmCalls = new AtomicInteger();
        AtomicInteger ollamaCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            vllmCalls.incrementAndGet();
            writeJson(exchange, 503, "{\"error\":\"unavailable\"}");
        });
        server.createContext("/api/chat", exchange -> {
            ollamaCalls.incrementAndGet();
            writeJson(exchange, 200, "{\"message\":{\"content\":\"The connector is charging. Choose another available connector.\"}}");
        });
        server.start();
        try {
            AiSupportProperties properties = properties("vllm,ollama", true, url(server), true, false, false, "");
            RoutingLlmClient client = new RoutingLlmClient(properties, new ObjectMapper(), new PiiRedactor());

            assertThat(client.complete(prompt()).provider()).isEqualTo("ollama");
            assertThat(client.complete(prompt()).provider()).isEqualTo("ollama");

            assertThat(vllmCalls.get()).isEqualTo(1);
            assertThat(ollamaCalls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static AiSupportProperties properties(String providerChain,
                                                   boolean vllmEnabled,
                                                   String providerBaseUrl,
                                                   boolean ollamaEnabled,
                                                   boolean geminiEnabled,
                                                   boolean hostedFallbackEnabled,
                                                   String geminiApiKey) {
        return new AiSupportProperties(
                true,
                "ollama",
                "support@electrahub.com",
                0,
                "http://session-service",
                "http://payment-service",
                "http://charger-service",
                "http://ocpp-service",
                200,
                400,
                900_000,
                "",
                "https://api.openai.com",
                providerBaseUrl,
                "legacy-model",
                0.12,
                180,
                2_000,
                providerChain,
                vllmEnabled,
                providerBaseUrl,
                "sparky-qwen3-8b",
                2_000,
                ollamaEnabled,
                "electrahub-sparky:4b",
                2_000,
                1,
                geminiEnabled,
                hostedFallbackEnabled,
                geminiApiKey,
                providerBaseUrl,
                "gemini-2.5-flash-lite",
                2_000,
                60_000
        );
    }

    private static LlmClient.LlmPrompt prompt() {
        return new LlmClient.LlmPrompt(
                "Is this charger available?",
                new ContextPayload("map", "charger", null, "EH-US-0001", "CON-US-0001", "LOC-1", null, "driver"),
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "check_charger_availability",
                        "The connector is charging and not available. Choose another available connector.",
                        "charger: EH-US-0001"),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of("connector CON-US-0001 is CHARGING available=false"), List.of())
        );
    }

    private static String url(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
