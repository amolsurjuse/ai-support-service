package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosticAnswerServiceTest {

    @Test
    void usesDriverLanguageForEvOwnerIdleStopFlow() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "liveCharging", "session", null, "EH-SFO-CHG-001", "CON-SFO-001", null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Remote stop was requested but idle fee is still running and receipt should not be generated yet.",
                context,
                "Bearer token"));

        assertThat(answer).contains("Your session stays active until the vehicle is unplugged");
        assertThat(answer).contains("Open the simulator link from the charging screen");
        assertThat(answer).doesNotContain("session-service state first");
    }

    @Test
    void keepsAdminDiagnosticsForSupportAudience() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "liveCharging", "session", null, "EH-SFO-CHG-001", "CON-SFO-001", null, null, "admin");

        String answer = service.renderForClient(service.answer(
                "Remote stop was requested but idle fee is still running and receipt should not be generated yet.",
                context,
                "Bearer token"));

        assertThat(answer).contains("Support should check session-service state first");
        assertThat(answer).contains("ocpp-service and simulator state");
        assertThat(answer).doesNotContain("Open the simulator link from the charging screen");
    }

    @Test
    void usesDriverLanguageForSimulatorSecurityCode() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "liveCharging", "session", null, "EH-SFO-CHG-001", "CON-SFO-001", null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "The simulator asks for security code when I open from mobile app to unplug. What should happen?",
                context,
                "Bearer token"));

        assertThat(answer).contains("You should not need to type it manually");
        assertThat(answer).contains("Tap unplug to finish the session");
        assertThat(answer).doesNotContain("emit the unplug/status events");
    }

    @Test
    void usesDriverLanguageForCardPresentReceipt() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "receipt", "session", null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "I tapped my credit card to charge. What should the receipt show?",
                context,
                "Bearer token"));

        assertThat(answer).contains("may not be linked to your app account");
        assertThat(answer).contains("Credit Card as the payment method");
        assertThat(answer).contains("masked card details");
        assertThat(answer).doesNotContain("admin should see");
    }

    private DiagnosticAnswerService service() {
        BackendDiagnosticsClient diagnosticsClient = mock(BackendDiagnosticsClient.class);
        when(diagnosticsClient.collect(any(), anyString())).thenReturn(
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of()));

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.available()).thenReturn(false);

        return new DiagnosticAnswerService(
                properties(),
                new PiiRedactor(),
                diagnosticsClient,
                llmClient);
    }

    private AiSupportProperties properties() {
        return new AiSupportProperties(
                true,
                "ollama",
                "support@electrahub.com",
                0,
                "http://session-service",
                "http://payment-service",
                "http://charger-service",
                "http://ocpp-service",
                500,
                "",
                "",
                "http://ollama",
                "electrahub-sparky",
                0.2,
                140,
                10_000);
    }
}
