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

class OllamaLlmClientTest {

    @Test
    void callsChatApiAndExtractsMessageContent() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"message\":{\"role\":\"assistant\",\"content\":\"The connector is already in use. Stop the active session first.\"},\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiSupportProperties properties = new AiSupportProperties(
                    true,
                    "ollama",
                    "support@electrahub.com",
                    0,
                    "http://session-service:8083",
                    "http://payment-service:8083",
                    "http://charger-management-service:8086",
                    "http://ocpp-service:8082",
                    100,
                    "",
                    "https://api.openai.com",
                    "http://localhost:" + server.getAddress().getPort(),
                    "electrahub-sparky",
                    0.2,
                    500,
                    2_000
            );
            OllamaLlmClient client = new OllamaLlmClient(properties, new ObjectMapper());
            LlmClient.LlmCompletion completion = client.complete(new LlmClient.LlmPrompt(
                    "Why did charging start fail?",
                    new ContextPayload("chargerDetail", "charging", null, "EH-1", "CON-1", "LOC-1", null, "driver"),
                    new DiagnosticAnswerService.DiagnosticAnswer("diagnose_charging_start", "Fallback answer", "connector active"),
                    new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of("connector CON-1 has active session"), List.of())
            ));

            assertThat(completion.ok()).isTrue();
            assertThat(completion.provider()).isEqualTo("ollama");
            assertThat(completion.model()).isEqualTo("electrahub-sparky");
            assertThat(completion.answer()).contains("connector is already in use");
            assertThat(requestBody.get()).contains("\"stream\":false");
            assertThat(requestBody.get()).contains("electrahub-sparky");
            assertThat(requestBody.get()).contains("connector CON-1 has active session");
            assertThat(requestBody.get()).contains("Project knowledge");
            assertThat(requestBody.get()).contains("session-service");
        } finally {
            server.stop(0);
        }
    }
}
