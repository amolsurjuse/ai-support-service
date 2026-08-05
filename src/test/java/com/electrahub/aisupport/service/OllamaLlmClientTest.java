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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaLlmClientTest {

    @Test
    void streamsEachOllamaNdjsonDelta() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"message\":{\"content\":\"The connector \"},\"done\":false}\n"
                    + "{\"message\":{\"content\":\"is available.\"},\"done\":false}\n"
                    + "{\"message\":{\"content\":\"\"},\"done\":true}\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OllamaLlmClient client = new OllamaLlmClient(
                    properties("http://localhost:" + server.getAddress().getPort()), new ObjectMapper());
            List<String> deltas = new ArrayList<>();

            LlmClient.LlmCompletion completion = client.completeStreaming(new LlmClient.LlmPrompt(
                    "Is this charger available?",
                    new ContextPayload("chargerDetail", "charger", null, "EH-1", "CON-1", "LOC-1", null, "driver"),
                    new DiagnosticAnswerService.DiagnosticAnswer(
                            "check_charger_availability", "The connector appears available.", "connector: CON-1"),
                    new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())), deltas::add);

            assertThat(completion.ok()).isTrue();
            assertThat(completion.answer()).isEqualTo("The connector is available.");
            assertThat(deltas).containsExactly("The connector ", "is available.");
            assertThat(requestBody.get()).contains("\"stream\":true");
        } finally {
            server.stop(0);
        }
    }

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
                    250,
                    900_000,
                    "",
                    "https://api.openai.com",
                    "http://localhost:" + server.getAddress().getPort(),
                    "electrahub-sparky",
                    0.2,
                    500,
                    2_000,
                    "ollama",
                    false,
                    "http://vllm:8000",
                    "sparky-qwen3-8b",
                    2_000,
                    true,
                    "electrahub-sparky",
                    2_000,
                    1,
                    false,
                    false,
                    "",
                    "https://generativelanguage.googleapis.com",
                    "gemini-2.5-flash-lite",
                    2_000,
                    30_000L
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
            assertThat(requestBody.get()).contains("\"think\":false");
            assertThat(requestBody.get()).contains("\"keep_alive\":\"30m\"");
            assertThat(requestBody.get()).contains("electrahub-sparky");
            assertThat(requestBody.get()).contains("connector CON-1 has active session");
            assertThat(requestBody.get()).contains("Project knowledge");
            assertThat(requestBody.get()).contains("Authoritative answer that must remain true");
            assertThat(requestBody.get()).contains("Exact supplied identifiers that must appear verbatim");
            assertThat(requestBody.get()).contains("CON-1");
            assertThat(requestBody.get()).doesNotContain("\"role\":\"system\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesPaymentAuthorizationInvariantForHighRiskPaymentPrompt() {
        String prompt = LlmPromptFormatter.promptText(new LlmClient.LlmPrompt(
                "What happens to a credit-card hold when remote start fails?",
                new ContextPayload("payments", "session", "S-1", "EH-1", "CON-1", "LOC-1", "S-1", "driver"),
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "explain_payment_authorization",
                        "Authorize the configured hold before remote start. If it fails, void or reverse the unused authorization promptly.",
                        "session: S-1"),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())
        ));

        assertThat(prompt).contains("Non-negotiable payment lifecycle rule");
        assertThat(prompt).contains("Do not say the hold was never applied");
        assertThat(prompt).contains("Capture only the final billable amount");
    }

    @Test
    void includesAnalyticsInvariantForMissingUsageAggregation() {
        String prompt = LlmPromptFormatter.promptText(new LlmClient.LlmPrompt(
                "What is my most used station?",
                new ContextPayload("dashboard", "analytics", null, null, null, null, null, "driver"),
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "explain_usage_analytics_gap",
                        "A completed-session aggregation is required. Do not invent a station.",
                        ""),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())
        ));

        assertThat(prompt).contains("Non-negotiable analytics rule");
        assertThat(prompt).contains("Do not replace it with vehicle trips");
    }

    @Test
    void includesOperationalChecklistForDashboardAttentionWithoutLiveFacts() {
        String prompt = LlmPromptFormatter.promptText(new LlmClient.LlmPrompt(
                "What needs attention on this dashboard?",
                new ContextPayload("dashboard", "dashboard", "dashboard", null, null, null, null, "admin"),
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "explain_dashboard_attention",
                        "Review scoped active idle/stuck sessions, failed starts, offline or faulted chargers, payment/settlement failures, and unread operational notifications.",
                        ""),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())
        ));

        assertThat(prompt).contains("Non-negotiable dashboard attention rule");
        assertThat(prompt).contains("offline or faulted chargers");
        assertThat(prompt).contains("payment or settlement failures");
    }

    @Test
    void includesExplicitAvailableAndLocationScopeInvariants() {
        String availablePrompt = LlmPromptFormatter.promptText(new LlmClient.LlmPrompt(
                "What should happen after an explicit Available status?",
                new ContextPayload("connectors", "connector", "CON-1", "EH-1", "CON-1", "LOC-1", null, "admin"),
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "explain_explicit_available_status",
                        "Available status must not carry a transaction id.",
                        "connector: CON-1"),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())
        ));
        String rbacPrompt = LlmPromptFormatter.promptText(new LlmClient.LlmPrompt(
                "What can a location administrator access?",
                new ContextPayload("rbac-policy", "access-policy", "location", null, null, "LOC-1", null, "admin"),
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "explain_rbac_scope",
                        "Location admins can access only assigned locations.",
                        "location: LOC-1"),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of())
        ));

        assertThat(availablePrompt).contains("must not carry a transaction id");
        assertThat(rbacPrompt).contains("Records from another location or operator must never be visible");
    }

    private static AiSupportProperties properties(String ollamaBaseUrl) {
        return new AiSupportProperties(
                true, "ollama", "support@electrahub.com", 0,
                "http://session-service", "http://payment-service", "http://charger-service", "http://ocpp-service",
                100, 250, 900_000, "", "https://api.openai.com", ollamaBaseUrl,
                "electrahub-sparky", 0.2, 180, 2_000, "ollama",
                false, "http://vllm:8000", "sparky-qwen3-8b", 2_000,
                true, "electrahub-sparky", 2_000, 1,
                false, false, "", "https://generativelanguage.googleapis.com",
                "gemini-2.5-flash-lite", 2_000, 30_000L);
    }
}
