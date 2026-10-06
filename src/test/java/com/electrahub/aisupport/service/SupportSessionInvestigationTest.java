package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportSessionInvestigationTest {
    @Test
    void bothChatModesKeepEvidenceInternalAndRemainUsefulDuringModelOutage() {
        var facts = new ArrayList<>(IntStream.range(0, 65).mapToObj(i -> "Session event " + i).toList());
        facts.add(0, "Session c3a62c5e-20f1-4d04-9209-55f3d63a59da: status=ACTIVE; startedAt=unavailable; stoppedAt=unavailable");
        var diagnostics = new BackendDiagnosticsClient.DiagnosticsSnapshot(
                facts, List.of("Payment hold unavailable"), List.of("get_service_topology: {\"resources\":[\"internal\"]}"));
        var backend = mock(BackendDiagnosticsClient.class);
        when(backend.collect(anyString(), any(), anyString(), any())).thenReturn(diagnostics);
        var llm = mock(LlmClient.class);
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm);
        var identity = new IdentityContext("tenant", "agent", Set.of("SUPPORT"), true);
        when(llm.available()).thenReturn(true);
        when(llm.complete(any())).thenReturn(LlmClient.LlmCompletion.success(
                "The session payment hold is unavailable. Check the recorded charging events before making a diagnosis.", "test", "test"));
        try {
            String answer = service.answer("Diagnose", context("support", "SELECTED_RECORD"), "Bearer token", identity).text();
            assertThat(answer).contains("recorded as active", "authorization amount unavailable")
                    .doesNotContain("Session event 64", "get_service_topology", "\"resources\"");
            verify(llm).complete(argThat(prompt -> prompt.diagnostics().facts().contains("Session event 64")));
            StringBuilder output = new StringBuilder();
            service.answerStreaming("Diagnose", context("support", "SELECTED_RECORD"), "Bearer token", identity, output::append);
            assertThat(output.toString()).isEqualTo(answer);
            when(llm.available()).thenReturn(false);
            assertThat(service.answer("Diagnose", context("support", "SELECTED_RECORD"), "Bearer token", identity).text())
                    .isEqualTo(answer);
        } finally { service.closeSupportSynthesis(); }
    }
    @Test
    void selectedSupportInvestigationNeverUsesAgentWalletOrDriverSessionApis() {
        var routes = new DiagnosticIntentRouter().route("Diagnose payment and billing", context("support", "SELECTED_RECORD"));
        assertThat(routes).containsExactly(DiagnosticIntentRouter.SESSION_INVESTIGATION);
    }

    @Test
    void driverAndKnowledgeRequestsDoNotUseAdministrativeInvestigation() {
        assertThat(new DiagnosticIntentRouter().route("Diagnose billing", context("driver", "SELECTED_RECORD")))
                .doesNotContain(DiagnosticIntentRouter.SESSION_INVESTIGATION);
        assertThat(DiagnosticIntentRouter.investigatesSession("Explain billing", context("support", "KNOWLEDGE"))).isFalse();
    }

    @Test
    void claimedSupportAudienceDoesNotGrantAccess() {
        var client = new SupportSessionDiagnosticsClient("http://127.0.0.1:1", new AiToolAuthorizationService(), mock(TenantAiPolicyService.class));
        assertThatThrownBy(() -> client.collect(context("support", "SELECTED_RECORD"), "Bearer token",
                new IdentityContext("tenant", "driver", Set.of(), true)))
                .hasMessageContaining("403");
    }

    @Test
    void tenantPolicyDenialStopsBeforeCallingGateway() {
        var policies = mock(TenantAiPolicyService.class);
        when(policies.policyFor("tenant")).thenReturn(new TenantAiPolicyService.TenantPolicy(
                "tenant", true, 1, 1, 1, List.of(), Set.of("admin.sessions.search")));
        var client = new SupportSessionDiagnosticsClient("http://127.0.0.1:1", new AiToolAuthorizationService(), policies);
        var result = client.collect(context("support", "SELECTED_RECORD"), "Bearer token",
                new IdentityContext("tenant", "agent", Set.of("SUPPORT"), true));
        assertThat(result.facts()).isEmpty();
        assertThat(result.gaps()).containsExactly("Selected-session investigation is not permitted by your role or tenant policy.");
    }

    @Test
    void broadAdminRolesCannotFetchEvidenceEvenWithForgedDriverAudience() {
        var policies = mock(TenantAiPolicyService.class);
        var client = new SupportSessionDiagnosticsClient("http://127.0.0.1:1", new AiToolAuthorizationService(), policies);
        for (String role : Set.of("ADMIN_READ_ONLY", "TENANT_ADMIN", "ENTERPRISE", "NETWORK", "LOCATION")) {
            assertThatThrownBy(() -> client.collect(context("driver", "SELECTED_RECORD"), "Bearer token",
                    new IdentityContext("tenant", "agent", Set.of(role), true))).hasMessageContaining("403");
        }
        verifyNoInteractions(policies);
    }

    @Test
    void fullEvidenceAndGapsSurvivePromptFormatting() {
        var facts = IntStream.range(0, 65).mapToObj(i -> "Event " + i + " at timestamp").toList();
        var diagnostics = new BackendDiagnosticsClient.DiagnosticsSnapshot(facts, List.of("Payment hold unavailable"));
        var fallback = new DiagnosticAnswerService.DiagnosticAnswer("diagnose_support_session", diagnostics.toInvestigationText(), "session");
        var prompt = new LlmClient.LlmPrompt("Diagnose", context("support", "SELECTED_RECORD"), fallback, diagnostics, "");
        assertThat(LlmPromptFormatter.promptText(prompt)).contains("Event 64 at timestamp", "Payment hold unavailable", "not OCPP");
    }

    private ContextPayload context(String audience, String mode) {
        String id = "c3a62c5e-20f1-4d04-9209-55f3d63a59da";
        return new ContextPayload("charging-sessions", "session", id, null, null, null, id, audience, Map.of("responseMode", mode));
    }
}
