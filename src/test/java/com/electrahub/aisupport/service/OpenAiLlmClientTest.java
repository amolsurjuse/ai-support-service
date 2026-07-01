package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiLlmClientTest {

    @Test
    void callsResponsesApiAndExtractsOutputText() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"output_text\":\"The charger is offline. Try another available connector.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiSupportProperties properties = new AiSupportProperties(
                    true,
                    "openai",
                    "support@electrahub.com",
                    0,
                    "http://session-service:8083",
                    "http://payment-service:8083",
                    "http://charger-management-service:8086",
                    "http://ocpp-service:8082",
                    100,
                    "test-key",
                    "http://localhost:" + server.getAddress().getPort(),
                    "gpt-4.1-mini",
                    0.2,
                    500,
                    2_000
            );
            OpenAiLlmClient client = new OpenAiLlmClient(properties, new ObjectMapper());
            LlmClient.LlmCompletion completion = client.complete(new LlmClient.LlmPrompt(
                    "Why did charging start fail?",
                    new ContextPayload("liveCharging", "charging", null, "EH-1", "CON-1", "LOC-1", null, "driver"),
                    new DiagnosticAnswerService.DiagnosticAnswer("diagnose_charging_start", "Fallback answer", "charger: EH-1"),
                    new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of("charger EH-1 status is UNAVAILABLE"), List.of())
            ));

            assertThat(completion.ok()).isTrue();
            assertThat(completion.answer()).contains("charger is offline");
            assertThat(requestBody.get()).contains("gpt-4.1-mini");
            assertThat(requestBody.get()).contains("charger EH-1 status is UNAVAILABLE");
            assertThat(requestBody.get()).doesNotContain("Bearer");
        } finally {
            server.stop(0);
        }
    }
}
