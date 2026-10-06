package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPromptIntentRegistryTest {
    private final AdminPromptIntentRegistry registry = new AdminPromptIntentRegistry();
    private final IdentityContext admin = new IdentityContext("synthetic-tenant", "synthetic-admin", Set.of("SYSTEM_ADMIN"), true);
    private ContextPayload context(String screen, String id, String mode) {
        return new ContextPayload(screen, screen, null, null, null, null, null, "admin", Map.of("promptIntent", id, "responseMode", mode));
    }

    @Test void everyReviewedGuideReturnsExactCompleteTextWithoutModelOrBackendRead() {
        var backend = mock(BackendDiagnosticsClient.class);
        var llm = mock(LlmClient.class);
        when(llm.available()).thenReturn(true);
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm);
        service.setPromptIntents(registry);
        try {
            assertThat(registry.guides()).hasSizeGreaterThanOrEqualTo(135);
            for (var guide : registry.guides().values()) {
                var context = context(guide.screen(), guide.id(), guide.responseMode());
                var answer = service.answer("unavailable charger payment revenue authorization", context, "Bearer synthetic", admin);
                assertThat(answer.text()).as(guide.id()).isEqualTo(guide.answer());
                assertThat(service.renderForClient(answer)).doesNotStartWith("I checked");
                StringBuilder streamed = new StringBuilder();
                service.answerStreaming("unavailable charger payment revenue authorization", context, "Bearer synthetic", admin, streamed::append);
                assertThat(streamed.toString()).as(guide.id()).isEqualTo(guide.answer());
            }
            verifyNoInteractions(backend, llm);
        } finally { service.closeSupportSynthesis(); }
    }

    @Test void crossScreenWrongModeUnknownAndRetiredIntentsFailClosed() {
        var guide = registry.guides().values().stream().filter(item -> !"charging-sessions".equals(item.screen())).findFirst().orElseThrow();
        for (var context : List.of(context("dashboard", guide.id(), guide.responseMode()),
                context(guide.screen(), guide.id(), "LIVE_LIST"), context("dashboard", "dashboard.offline-chargers", "LIVE_SUMMARY"),
                context("dashboard", "injected.arbitrary-tool", "LIVE_SUMMARY"))) {
            assertThat(registry.answer("query everything", context).orElseThrow().toolName()).isEqualTo("admin.prompt.unsupported");
        }
        assertThat(registry.answer("current revenue", context("dashboard", "dashboard.revenue", "LIVE_SUMMARY"))).isEmpty();
        assertThat(registry.answer("current revenue", context("users", "dashboard.revenue", "LIVE_SUMMARY")).orElseThrow().toolName()).isEqualTo("admin.prompt.unsupported");
    }

    @Test void unknownFreeformOnGuidanceScreenCannotBorrowChargingOrPaymentTools() {
        var context = new ContextPayload("payment-gateways", "payment-gateway", null, null, null, null, null, "admin");
        var answer = registry.answer("Why is authorization unavailable for this network?", context).orElseThrow();
        assertThat(answer.toolName()).isEqualTo("admin.guide.screen");
        assertThat(answer.text()).contains("not connected").doesNotContain("Most likely causes", "pick another connector");
    }

    @Test void guideRegistryNeverBypassesAdministrativeRoleChecks() {
        var backend = mock(BackendDiagnosticsClient.class); var llm = mock(LlmClient.class);
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm);
        service.setPromptIntents(registry);
        var guide = registry.guides().values().iterator().next();
        try {
            assertThatThrownBy(() -> service.answer("guide", context(guide.screen(), guide.id(), guide.responseMode()), "Bearer synthetic",
                    new IdentityContext("tenant", "driver", Set.of("DRIVER"), true))).hasMessageContaining("403");
            assertThatThrownBy(() -> service.answer("precheck", context("charging-sessions", "sessions.stop-precheck", "CHANGE_PRECHECK"), "Bearer synthetic",
                    new IdentityContext("tenant", "reader", Set.of("ADMIN_READ_ONLY"), true))).hasMessageContaining("403");
            verifyNoInteractions(backend, llm);
        } finally { service.closeSupportSynthesis(); }
    }

    @Test void sessionIntentsRejectMissingInvalidOrConflictingSessionSelectionBeforeAnyToolOrModel() {
        var backend = mock(BackendDiagnosticsClient.class); var llm = mock(LlmClient.class);
        var commands = mock(AdminCommandService.class); var router = mock(JevSupportRouter.class);
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm, commands, null, null);
        service.setPromptIntents(registry); service.setSupportRouter(router);
        String valid = "11111111-2222-4333-8444-555555555555";
        try {
            for (String intent : List.of("sessions.overview", "sessions.stuck", "sessions.meter-cost", "sessions.authorization")) {
                var attributes = Map.of("promptIntent", intent, "responseMode", "SELECTED_RECORD");
                for (var invalid : List.of(
                        new ContextPayload("charging-sessions", "session", valid, null, null, null, null, "admin", attributes),
                        new ContextPayload("charging-sessions", "session", null, "synthetic-charger", "synthetic-connector", "synthetic-location", null, "admin", attributes),
                        new ContextPayload("charging-sessions", "session", null, null, null, null, "not-a-session", "admin", attributes),
                        new ContextPayload("charging-sessions", "session", null, null, null, null, "1-1-1-1-1", "admin", attributes),
                        new ContextPayload("charging-sessions", "session", "99999999-2222-4333-8444-555555555555", null, null, null, valid, "admin", attributes))) {
                    var answer = service.answer("Explain payment billing and latest meter values", invalid, "Bearer synthetic", admin);
                    assertThat(answer.toolName()).as(intent).isEqualTo("admin.selected-record.required");
                    assertThat(answer.text()).contains("Select a valid charging session", "No records were queried");
                    StringBuilder stream = new StringBuilder();
                    service.answerStreaming("Explain payment billing and latest meter values", invalid, "Bearer synthetic", admin, stream::append);
                    assertThat(stream.toString()).isEqualTo(answer.text());
                }
            }
            verifyNoInteractions(backend, llm, commands, router);
        } finally { service.closeSupportSynthesis(); }
    }

    @Test void validSessionSelectionReachesOnlyTheExistingSessionInvestigationPath() {
        String id = "abcdefab-2222-4333-8444-555555555555";
        var backend = mock(BackendDiagnosticsClient.class); var llm = mock(LlmClient.class);
        when(backend.collect(anyString(), any(), anyString(), any())).thenReturn(new BackendDiagnosticsClient.DiagnosticsSnapshot(
                List.of("Session " + id + ": status=COMPLETED; startedAt=unavailable; stoppedAt=unavailable"), List.of()));
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm);
        service.setPromptIntents(registry);
        try {
            for (String intent : List.of("sessions.overview", "sessions.stuck", "sessions.meter-cost", "sessions.authorization")) {
                var selected = new ContextPayload("charging-sessions", "session", id.toUpperCase(Locale.ROOT), "synthetic-charger", null, null, id,
                        "admin", Map.of("promptIntent", intent, "responseMode", "SELECTED_RECORD"));
                assertThat(registry.answer("question", selected)).isEmpty();
                var sessionOnly = new ContextPayload("charging-sessions", "session", null, null, null, null, id,
                        "admin", Map.of("promptIntent", intent, "responseMode", "SELECTED_RECORD"));
                assertThat(registry.answer("question", sessionOnly)).isEmpty();
                assertThat(new DiagnosticIntentRouter().route("question", selected)).containsExactly(DiagnosticIntentRouter.SESSION_INVESTIGATION);
                assertThat(service.answer("question", selected, "Bearer synthetic", admin).toolName()).isEqualTo("diagnose_support_session");
            }
            verify(backend, times(4)).collect(anyString(), any(), eq("Bearer synthetic"), eq(admin));
            verifyNoInteractions(llm);
        } finally { service.closeSupportSynthesis(); }
    }
}
