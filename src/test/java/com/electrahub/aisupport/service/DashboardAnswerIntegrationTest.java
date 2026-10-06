package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DashboardAnswerIntegrationTest {
    private final IdentityContext admin = new IdentityContext("tenant", "agent", Set.of("SYSTEM_ADMIN"), true);
    private final ContextPayload context = new ContextPayload("dashboard", "dashboard", null, null, null, null, null,
            "admin", Map.of("responseMode", "LIVE_SUMMARY", "totalRevenue", "999999.99"));

    @Test void bothTransportsReturnVerifiedFactsAndGapsWithoutModelRewritingOrClientTotals() {
        var backend = mock(BackendDiagnosticsClient.class);
        var snapshot = new BackendDiagnosticsClient.DiagnosticsSnapshot(
                List.of("2 of 10 eligible sessions failed in the selected period.", "Completed revenue: EUR 12.34."),
                List.of("Charger health could not be checked for these filters."));
        when(backend.collect(anyString(), any(), anyString(), any())).thenReturn(snapshot);
        var llm = mock(LlmClient.class);
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm);
        try {
            String text = service.answer("What needs attention right now?", context, "Bearer original", admin).text();
            assertThat(text).contains("2 of 10", "EUR 12.34", "Charger health could not be checked")
                    .doesNotContain("999999", "No specific incident", "Review the scoped dashboard");
            StringBuilder stream = new StringBuilder();
            service.answerStreaming("What needs attention right now?", context, "Bearer original", admin, stream::append);
            assertThat(stream.toString()).isEqualTo(text);
            verifyNoInteractions(llm);
        } finally { service.closeSupportSynthesis(); }
    }

    @Test void missingLiveEvidenceDoesNotBecomeAHealthyDashboardClaim() {
        var backend = mock(BackendDiagnosticsClient.class);
        when(backend.collect(anyString(), any(), anyString(), any())).thenReturn(
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of("Current session snapshot unavailable.")));
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, mock(LlmClient.class));
        try {
            assertThat(service.answer("What needs attention right now?", context, "Bearer original", admin).text())
                    .contains("could not verify", "No specific incident is confirmed", "Current session snapshot unavailable")
                    .doesNotContain("No incidents found", "All systems healthy", "999999");
        } finally { service.closeSupportSynthesis(); }
    }

    @Test void dashboardQuickPromptsBypassLegacyAdminToolsThatDoNotCarryTheCurrentFilters() {
        var backend = mock(BackendDiagnosticsClient.class);
        when(backend.collect(anyString(), any(), anyString(), any())).thenReturn(
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of("Selected-period scoped evidence."), List.of()));
        var legacyClient = mock(AdminReadOnlyToolClient.class);
        var mutations = mock(AdminMutationService.class);
        var registry = mock(AdminToolRegistry.class);
        var policies = mock(TenantAiPolicyService.class);
        var commands = new AdminCommandService(new AdminCommandPlanner(), registry, legacyClient,
                new com.electrahub.aisupport.security.AiToolAuthorizationService(), mutations, policies);
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend,
                mock(LlmClient.class), commands, null, null);
        try {
            for (String question : List.of("How is total revenue calculated for this filter?",
                    "Which sessions are stuck, idle, or unsettled?", "Which chargers are offline or faulted?",
                    "What is the charging success rate for this period?")) {
                assertThat(service.answer(question, context, "Bearer original", admin).toolName()).isEqualTo("summarize_dashboard");
                StringBuilder stream = new StringBuilder();
                service.answerStreaming(question, context, "Bearer original", admin, stream::append);
                assertThat(stream.toString()).contains("Selected-period scoped evidence.");
            }
            verifyNoInteractions(legacyClient, mutations, registry, policies);
        } finally { service.closeSupportSynthesis(); }
    }
}
