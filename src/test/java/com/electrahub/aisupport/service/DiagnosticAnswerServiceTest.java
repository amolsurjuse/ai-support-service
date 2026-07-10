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

    @Test
    void explainsTotalRevenueDashboardMetric() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "dashboard", "dashboard", null, null, null, null, null, "admin");

        String answer = service.renderForClient(service.answer(
                "Total revenue",
                context,
                "Bearer token"));

        assertThat(answer).contains("completed charging revenue");
        assertThat(answer).contains("selected dashboard date filter");
        assertThat(answer).contains("completed charging sessions and receipts");
        assertThat(answer).doesNotContain("start fails");
    }

    @Test
    void reportsSelectedBusyChargerAsUnavailable() {
        DiagnosticAnswerService service = serviceWithDiagnostics(
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(
                        "charger EH-SFO-CHG-001 status is CHARGING with 0 available port(s) and 1 busy port(s)",
                        "connector CON-SFO-001 is CHARGING available=false power=150 kW"
                ), List.of()));
        ContextPayload context = new ContextPayload(
                "map", "charger", "EH-SFO-CHG-001", "EH-SFO-CHG-001", "CON-SFO-001", "US*EHB*LOC*SFO001", null, "driver");

        String answer = service.renderForClient(service.answer(
                "Is this charger available?",
                context,
                "Bearer token"));

        assertThat(answer).contains("No, this charger is not available right now");
        assertThat(answer).contains("0 available port(s)");
        assertThat(answer).contains("1 busy port(s)");
        assertThat(answer).contains("connector status is CHARGING");
        assertThat(answer).doesNotContain("Yes, this charger appears available");
    }

    @Test
    void reportsSelectedAvailableChargerAsAvailable() {
        DiagnosticAnswerService service = serviceWithDiagnostics(
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(
                        "charger EH-SFO-CHG-002 status is AVAILABLE with 1 available port(s) and 0 busy port(s)",
                        "connector CON-SFO-002 is AVAILABLE available=true power=150 kW"
                ), List.of()));
        ContextPayload context = new ContextPayload(
                "map", "charger", "EH-SFO-CHG-002", "EH-SFO-CHG-002", "CON-SFO-002", "US*EHB*LOC*SFO001", null, "driver");

        String answer = service.renderForClient(service.answer(
                "Is this charger available?",
                context,
                "Bearer token"));

        assertThat(answer).contains("Yes, this charger appears available right now");
        assertThat(answer).contains("1 available port(s)");
        assertThat(answer).contains("0 busy port(s)");
        assertThat(answer).doesNotContain("not available right now");
    }

    @Test
    void routesAdminIdleRemoteStopPromptToAdminDiagnostics() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "charging-sessions", "session", null, null, null, null, null, "admin");

        String answer = service.renderForClient(service.answer(
                "Why is a session still idle after remote stop?",
                context,
                "Bearer token"));

        assertThat(answer).contains("session-service state first");
        assertThat(answer).contains("remoteStopRequestedAt");
        assertThat(answer).contains("ocpp-service and simulator state");
        assertThat(answer).doesNotContain("Your session stays active");
    }

    @Test
    void routesStartFailurePromptWithoutInventingLiveState() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "liveCharging", "charging", null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Why did start fail?",
                context,
                "Bearer token"));

        assertThat(answer).contains("charging start failure");
        assertThat(answer).contains("I do not have a selected charger, connector, or session id");
        assertThat(answer).doesNotContain("What should admin see");
    }

    @Test
    void routesStuckPromptWithoutUnrelatedPaymentAdviceWhenContextMissing() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "liveCharging", "charging", null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Why is charging stuck?",
                context,
                "Bearer token"));

        assertThat(answer).contains("waiting for the charger to confirm charging has begun");
        assertThat(answer).contains("I do not have the active session or charger id");
        assertThat(answer).doesNotContain("saved payment cards");
        assertThat(answer).doesNotContain("auto top-up");
    }

    @Test
    void refusesMonthlySpendWithoutAggregationApi() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "dashboard", null, null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "How much did I spend last month?",
                context,
                "Bearer token"));

        assertThat(answer).contains("cannot calculate monthly spend from chat yet");
        assertThat(answer).contains("dated receipt/session aggregation API");
        assertThat(answer).doesNotContain("What should admin see");
    }

    @Test
    void asksForSelectedSessionForLastReceipt() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "history", null, null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Show last receipt",
                context,
                "Bearer token"));

        assertThat(answer).contains("cannot open a receipt from chat unless a specific session is selected");
        assertThat(answer).contains("Open the charging history entry");
    }

    @Test
    void refusesTripDistanceData() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "history", null, null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Trips over 100 km",
                context,
                "Bearer token"));

        assertThat(answer).contains("does not have trip distance data");
        assertThat(answer).contains("vehicle trip telemetry");
    }

    @Test
    void asksForPricingContextBeforeComparingPlans() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "payments", null, null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Compare pricing plans",
                context,
                "Bearer token"));

        assertThat(answer).contains("cannot compare pricing plans precisely");
        assertThat(answer).contains("selected tariff, charger, location, or pricing-plan report");
    }

    private DiagnosticAnswerService service() {
        return serviceWithDiagnostics(new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of()));
    }

    private DiagnosticAnswerService serviceWithDiagnostics(BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        BackendDiagnosticsClient diagnosticsClient = mock(BackendDiagnosticsClient.class);
        when(diagnosticsClient.collect(any(), anyString())).thenReturn(diagnostics);

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
