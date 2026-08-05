package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class DiagnosticAnswerServiceTest {

    @Test
    void answersIdentityWithoutDiagnosticsOrChargerContext() {
        BackendDiagnosticsClient diagnosticsClient = mock(BackendDiagnosticsClient.class);
        LlmClient llmClient = mock(LlmClient.class);
        DiagnosticAnswerService service = new DiagnosticAnswerService(
                properties(), new PiiRedactor(), diagnosticsClient, llmClient);
        ContextPayload context = new ContextPayload(
                "chargerDetail", "charger", null, "EH-US-CHG-0001", "CON-US-0001", "LOC-1", null, "driver");

        DiagnosticAnswerService.DiagnosticAnswer answer = service.answer(
                "What is your name?", context, "Bearer token");

        assertThat(answer.toolName()).isEqualTo("assistant_identity");
        assertThat(service.renderForClient(answer)).isEqualTo("I'm Sparky, ElectraHub's EV charging assistant.");
        assertThat(answer.text()).doesNotContain("EH-US-CHG-0001", "CON-US-0001", "LOC-1");
        verifyNoInteractions(diagnosticsClient, llmClient);
    }

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
    void reportsExactRevenueFromLiveDashboardContext() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "dashboard", "analytics", null, null, null, null, null, "admin",
                Map.of(
                        "totalRevenue", "72127.03",
                        "currency", "USD",
                        "totalSessions", "1721",
                        "from", "2026-07-01T00:00:00Z",
                        "to", "2026-07-14T23:59:59Z",
                        "filterLocationId", "all"
                ));

        String answer = service.renderForClient(service.answer("What is total revenue?", context, "Bearer token"));

        assertThat(answer).contains("USD 72,127.03");
        assertThat(answer).contains("1,721 completed session(s)");
        assertThat(answer).contains("live analytics response");
        assertThat(answer).doesNotContain("Admin should verify");
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
    void usesQualityApprovedLlmAnswerForAvailabilityPrompt() {
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics = new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(
                "charger EH-SFO-CHG-002 status is AVAILABLE with 1 available port(s) and 0 busy port(s)",
                "connector CON-SFO-002 is AVAILABLE available=true power=150 kW"
        ), List.of());
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.available()).thenReturn(true);
        when(llmClient.complete(any())).thenReturn(LlmClient.LlmCompletion.success(
                "Yes. The selected connector is available right now, with one open port. Start charging only after the connector still shows Available on the screen.",
                "ollama",
                "electrahub-sparky:8b"));

        DiagnosticAnswerService service = serviceWithLlm(diagnostics, llmClient);
        ContextPayload context = new ContextPayload(
                "map", "charger", "EH-SFO-CHG-002", "EH-SFO-CHG-002", "CON-SFO-002", "US*EHB*LOC*SFO001", null, "driver");

        String answer = service.renderForClient(service.answer("Is this charger available?", context, "Bearer token"));

        assertThat(answer).contains("one open port");
        assertThat(answer).doesNotContain("session-service");
        verify(llmClient).complete(any());
    }

    @Test
    void fallsBackWhenLlmLeaksPromptContent() {
        BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics = new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(
                "charger EH-SFO-CHG-002 status is AVAILABLE with 1 available port(s) and 0 busy port(s)",
                "connector CON-SFO-002 is AVAILABLE available=true power=150 kW"
        ), List.of());
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.available()).thenReturn(true);
        when(llmClient.complete(any())).thenReturn(LlmClient.LlmCompletion.success(
                "The authoritative draft says the connector is available.",
                "ollama",
                "electrahub-sparky:8b"));

        DiagnosticAnswerService service = serviceWithLlm(diagnostics, llmClient);
        ContextPayload context = new ContextPayload(
                "map", "charger", "EH-SFO-CHG-002", "EH-SFO-CHG-002", "CON-SFO-002", "US*EHB*LOC*SFO001", null, "driver");

        String answer = service.renderForClient(service.answer("Is this charger available?", context, "Bearer token"));

        assertThat(answer).contains("Yes, this charger appears available right now");
        assertThat(answer).doesNotContain("authoritative draft");
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
    void doesNotTreatMissingSessionContextAsTheReasonAPastChargeFailed() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "dashboard", null, null, null, null, null, null, "driver");

        String answer = service.renderForClient(service.answer(
                "Why did my last charge fail?",
                context,
                "Bearer token"));

        assertThat(answer).contains("cannot diagnose why a past charging session failed without the selected session");
        assertThat(answer).contains("Missing session context is not itself the reason for the failure");
    }

    @Test
    void usesSelectedSessionForPastSessionDiagnosis() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "history", "session", "S-123", "EH-1", "CON-1", "LOC-1", "S-123", "driver");

        String answer = service.renderForClient(service.answer(
                "Why did my last charge fail?",
                context,
                "Bearer token"));

        assertThat(answer).contains("selected charging session S-123");
        assertThat(answer).contains("session state, stop reason, charger and connector events");
        assertThat(answer).doesNotContain("without the selected session");
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

    @Test
    void explainsCardAuthorizationReversalWithoutChangingPaymentOutcome() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "payments", "session", "S-1", "EH-1", "CON-1", "LOC-1", "S-1", "driver");

        String answer = service.renderForClient(service.answer(
                "What happens to my credit-card hold if remote start fails?",
                context,
                "Bearer token"));

        assertThat(answer).contains("authorize the configured payment hold before remote start");
        assertThat(answer).contains("voided or reversed promptly");
        assertThat(answer).contains("release any unused hold");
    }

    @Test
    void rejectsUnknownRfidBeforeSessionCreation() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "simulator", "connector", "CON-1", "EH-1", "CON-1", "LOC-1", null, "admin");

        String answer = service.renderForClient(service.answer(
                "Can an unknown RFID tag start a session?",
                context,
                "Bearer token"));

        assertThat(answer).contains("unknown or unauthorized tag must be rejected");
        assertThat(answer).contains("must not create a transaction or charging session");
    }

    @Test
    void explainsPlugAndChargeCertificateFailure() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "simulator", "connector", "CON-1", "EH-1", "CON-1", "LOC-1", null, "admin");

        String answer = service.renderForClient(service.answer(
                "What happens when Plug and Charge certificate validation fails?",
                context,
                "Bearer token"));

        assertThat(answer).contains("EMAID and contract certificate");
        assertThat(answer).contains("authorization must be rejected");
    }

    @Test
    void keepsRealTimeCostCalculationOnBackend() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "liveCharging", "session", "S-1", "EH-1", "CON-1", "LOC-1", "S-1", "driver");

        String answer = service.renderForClient(service.answer(
                "Why is the real time cost calculation incorrect?",
                context,
                "Bearer token"));

        assertThat(answer).contains("calculated by the backend, not by the app");
        assertThat(answer).contains("session and idle-fee caps");
    }

    @Test
    void explainsNotificationStateAndDeduplication() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "notifications", "session", "S-1", "EH-1", "CON-1", "LOC-1", "S-1", "driver");

        String answer = service.renderForClient(service.answer(
                "Why did I get a duplicate idle notification while charging?",
                context,
                "Bearer token"));

        assertThat(answer).contains("backend confirms the matching session state");
        assertThat(answer).contains("must not create duplicate notifications");
    }

    @Test
    void findsAlternativeCcsChargersFromLiveInventoryContext() {
        DiagnosticAnswerService service = serviceWithDiagnostics(
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of()),
                new BackendDiagnosticsClient.ChargerAlternatives(
                        "CCS",
                        "ElectraHub Fresno Site 067",
                        List.of(new BackendDiagnosticsClient.ChargerAlternative(
                                "EH-US-CHG-0332",
                                "ElectraHub US Site 067 Charger 2",
                                "CON-US-0332",
                                "ElectraHub Fresno Site 067",
                                0.4,
                                "0.4 mi",
                                "CCS1 / 150 kW"))));
        ContextPayload context = new ContextPayload(
                "map", "charger", "EH-US-CHG-0331", "EH-US-CHG-0331", "CON-US-0331", "US*EHB*LOC*USA067", null, "driver");

        String answer = service.renderForClient(service.answer(
                "Find another CCS charger",
                context,
                "Bearer token"));

        assertThat(answer).contains("I found these available CCS options near ElectraHub Fresno Site 067");
        assertThat(answer).contains("EH-US-CHG-0332");
        assertThat(answer).contains("CON-US-0332");
        assertThat(answer).contains("0.4 mi away");
        assertThat(answer).doesNotContain("I cannot search for another charger from chat yet");
    }

    @Test
    void reportsNoAlternativeChargerWithoutInventingResults() {
        DiagnosticAnswerService service = service();
        ContextPayload context = new ContextPayload(
                "map", "charger", "EH-US-CHG-0331", "EH-US-CHG-0331", "CON-US-0331", "US*EHB*LOC*USA067", null, "driver");

        String answer = service.renderForClient(service.answer(
                "Find another CCS charger",
                context,
                "Bearer token"));

        assertThat(answer).contains("I checked live charger inventory");
        assertThat(answer).contains("could not find another available CCS connector");
        assertThat(answer).doesNotContain("I cannot search for another charger from chat yet");
    }

    @Test
    void groundsEveryAdminQuickPromptCategoryInOperationalRules() {
        DiagnosticAnswerService service = service();
        ContextPayload dashboard = new ContextPayload("dashboard", "dashboard", null, null, null, null, null, "admin");
        ContextPayload sessions = new ContextPayload("charging-sessions", "session", null, null, null, null, null, "admin");
        ContextPayload chargers = new ContextPayload("chargers", "charger", null, null, null, null, null, "admin");
        ContextPayload pricing = new ContextPayload("pricing", "tariff", null, null, null, null, null, "admin");
        ContextPayload subscriptions = new ContextPayload("subscriptions", "subscription", null, null, null, null, null, "admin");
        ContextPayload rbac = new ContextPayload("rbac-policy", "admin", null, null, null, null, null, "admin");
        ContextPayload notifications = new ContextPayload("notifications", "notification", null, null, null, null, null, "admin");

        assertThat(service.answer("What needs attention on this dashboard?", dashboard, "Bearer token").text())
                .contains("failed starts", "offline or faulted chargers");
        assertThat(service.answer("What should I monitor for charging success?", dashboard, "Bearer token").text())
                .contains("eligible charging attempts", "Manual driver cancellations");
        assertThat(service.answer("What should I check before remotely stopping an active session?", sessions, "Bearer token").text())
                .contains("idle fees are enabled", "until unplug");
        assertThat(service.answer("What should I check before changing charger status?", chargers, "Bearer token").text())
                .contains("last OCPP heartbeat", "Inoperative");
        assertThat(service.answer("What should happen after an explicit Available status?", chargers, "Bearer token").text())
                .contains("must not include a transaction id", "terminal state");
        assertThat(service.answer("How do idle-fee and session caps work?", pricing, "Bearer token").text())
                .contains("enforced by the backend", "idle-fee cap");
        assertThat(service.answer("Why must receipt totals match active-session cost?", pricing, "Bearer token").text())
                .contains("final billable record", "Do not calculate or correct the total in the UI");
        assertThat(service.answer("Why was a subscription discount not applied?", subscriptions, "Bearer token").text())
                .contains("scope matches", "remaining quota");
        assertThat(service.answer("How is subscription quota consumed?", subscriptions, "Bearer token").text())
                .contains("eligible energy", "atomically");
        assertThat(service.answer("What should happen when a quota is exhausted?", subscriptions, "Bearer token").text())
                .contains("normal applicable tariff", "remaining quota is zero");
        assertThat(service.answer("What can a location administrator access?", rbac, "Bearer token").text())
                .contains("server-derived role and data scope", "location's chargers");
        assertThat(service.answer("Why is this administrator receiving Forbidden?", rbac, "Bearer token").text())
                .contains("Forbidden response", "does not permit that operation");
        assertThat(service.answer("Why was this notification generated?", notifications, "Bearer token").text())
                .contains("backend confirms", "delivery state");
        assertThat(service.answer("What should happen when push delivery fails?", notifications, "Bearer token").text())
                .contains("retry transient Firebase failures", "dead-letter");
    }

    private DiagnosticAnswerService service() {
        return serviceWithDiagnostics(new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of()));
    }

    private DiagnosticAnswerService serviceWithDiagnostics(BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics) {
        return serviceWithDiagnostics(
                diagnostics,
                new BackendDiagnosticsClient.ChargerAlternatives("CCS", "", List.of()));
    }

    private DiagnosticAnswerService serviceWithDiagnostics(BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics,
                                                           BackendDiagnosticsClient.ChargerAlternatives alternatives) {
        BackendDiagnosticsClient diagnosticsClient = mock(BackendDiagnosticsClient.class);
        when(diagnosticsClient.collect(any(), anyString())).thenReturn(diagnostics);
        when(diagnosticsClient.findChargerAlternatives(any(), anyString(), anyInt())).thenReturn(alternatives);

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.available()).thenReturn(false);

        return new DiagnosticAnswerService(
                properties(),
                new PiiRedactor(),
                diagnosticsClient,
                llmClient);
    }

    private DiagnosticAnswerService serviceWithLlm(BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics,
                                                   LlmClient llmClient) {
        BackendDiagnosticsClient diagnosticsClient = mock(BackendDiagnosticsClient.class);
        when(diagnosticsClient.collect(any(), anyString())).thenReturn(diagnostics);
        when(diagnosticsClient.findChargerAlternatives(any(), anyString(), anyInt()))
                .thenReturn(new BackendDiagnosticsClient.ChargerAlternatives("CCS", "", List.of()));

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
                1_000,
                900_000,
                "",
                "",
                "http://ollama",
                "electrahub-sparky",
                0.2,
                140,
                10_000,
                "ollama",
                false,
                "http://vllm:8000",
                "sparky-qwen3-8b",
                10_000,
                true,
                "electrahub-sparky",
                10_000,
                1,
                false,
                false,
                "",
                "https://generativelanguage.googleapis.com",
                "gemini-2.5-flash-lite",
                10_000,
                30_000L);
    }
}
