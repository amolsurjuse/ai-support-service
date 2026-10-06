package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportMcpInvestigationServiceTest {
    private final String id = "c3a62c5e-20f1-4d04-9209-55f3d63a59da";
    private final IdentityContext identity = new IdentityContext("tenant", "agent", Set.of("SUPPORT"), true);
    private final ContextPayload context = new ContextPayload("charging-sessions", "session", id, null, null, null,
            id, "support", Map.of("responseMode", "SELECTED_RECORD"));
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SupportMcpClient mcp = mock(SupportMcpClient.class);
    private final TenantAiPolicyService policies = mock(TenantAiPolicyService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final SupportMcpInvestigationService service = new SupportMcpInvestigationService(mcp,
            new AiToolAuthorizationService(), policies, llm, mapper, new PiiRedactor(), true);

    @AfterEach void close() { service.close(); }
    private void allow(String... tools) {
        when(policies.policyFor("tenant")).thenReturn(new TenantAiPolicyService.TenantPolicy(
                "tenant", true, 1, 1, 1, List.of(), Set.of(tools)));
    }
    private void evidence(String session, Instant collected) {
        when(mcp.call(eq("get_session_evidence"), eq(Map.of("sessionId", id)), eq(identity), eq("Bearer token"), any()))
                .thenReturn(mapper.valueToTree(Map.of("sessionId", session, "collectedAt", collected.toString(),
                        "facts", List.of("Lifecycle assessment: start phase incomplete", "Payment authorization: 20 EUR"),
                        "gaps", List.of("Capture not supplied"),
                        "organizationContext", Map.of("sessionId", session, "collectedAt", collected.toString(),
                                "requesterTenantId", "tenant", "organizationContext", Map.of("locationId", "assigned-location")))));
    }

    @Test void roleAndPolicyDenialsNeverReachMcpOrModel() {
        for (String role : List.of("TENANT_ADMIN", "ADMIN_READ_ONLY", "NETWORK_ADMIN", "DRIVER")) {
            assertThatThrownBy(() -> service.collect("Diagnose", context, "Bearer token",
                    new IdentityContext("tenant", "actor", Set.of(role), true))).hasMessageContaining("403");
        }
        allow("admin.sessions.search");
        assertThat(service.collect("Diagnose", context, "Bearer token", identity).gaps()).containsExactly(
                "Selected-session investigation is not permitted by your tenant policy.");
        verifyNoInteractions(mcp, llm);
    }

    @Test void selectedSessionIsMandatoryAndModelCannotChangeArguments() {
        allow("*"); evidence(id, Instant.now());
        when(llm.available()).thenReturn(true);
        when(llm.complete(any())).thenReturn(LlmClient.LlmCompletion.success(
                "{\"tools\":[\"get_org_context\"]}", "test", "test"));
        when(mcp.call(eq("get_flow_definition"), anyMap(), eq(identity), eq("Bearer token"), any()))
                .thenReturn(mapper.valueToTree(Map.of("flowId", "charging-session", "sourceRevision", "revision")));
        var report = service.collect("Why is this stuck? customer@example.com", context, "Bearer token", identity);
        assertThat(report.facts()).contains("Payment authorization: 20 EUR");
        assertThat(report.references().toString()).contains("assigned-location", "sourceRevision");
        verify(mcp, never()).call(eq("get_org_context"), anyMap(), any(), anyString(), any());
        verify(mcp).call(eq("get_flow_definition"), eq(Map.of("flowId", "charging-session")), eq(identity), eq("Bearer token"), any());
        verify(mcp, never()).call(eq("get_service_topology"), anyMap(), any(), anyString(), any());
        var prompt = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmPrompt.class);
        verify(llm).complete(prompt.capture());
        assertThat(prompt.getValue().context()).isNull();
        assertThat(prompt.getValue().userMessage()).doesNotContain("customer@example.com");
        assertThat(prompt.getValue().diagnostics().facts()).isEmpty();
    }

    @Test void malformedPlansCannotAddToolsArgumentsOrRemoveRequiredFlow() {
        for (String plan : List.of("{\"tools\":[\"execute_refund\"]}", "{\"tools\":[\"get_org_context\"],\"sessionId\":\"other\"}",
                "{\"tools\":[\"get_org_context\",\"get_org_context\"]}", "prose", "[]")) {
            assertThat(service.parsePlan(plan)).isEqualTo(SupportMcpInvestigationService.CONTEXT_TOOLS);
        }
        assertThat(service.parsePlan("{\"tools\":[]}")).containsExactly("get_flow_definition");
    }

    @Test void staleWrongSessionAndFailedMcpNeverReachPlannerOrFallbackTransport() {
        allow("*"); evidence(UUID.randomUUID().toString(), Instant.now());
        assertThat(service.collect("Diagnose", context, "Bearer token", identity).facts()).isEmpty();
        evidence(id, Instant.now().minusSeconds(200));
        assertThat(service.collect("Diagnose", context, "Bearer token", identity).gaps().toString()).contains("stale");
        doThrow(new IllegalStateException("denied")).when(mcp).initialize(eq(identity), eq("Bearer token"), any());
        assertThat(service.collect("Diagnose", context, "Bearer token", identity).gaps().toString()).contains("Session evidence service is unavailable");
        verifyNoInteractions(llm);
        verify(mcp, never()).call(eq("get_org_context"), anyMap(), any(), anyString(), any());
    }

    @Test void contextPolicyDenialsPreserveEvidenceWithoutExtraReads() {
        allow("admin.sessions.diagnose"); evidence(id, Instant.now());
        var report = service.collect("Diagnose", context, "Bearer token", identity);
        assertThat(report.facts()).contains("Payment authorization: 20 EUR");
        assertThat(report.gaps()).hasSize(4);
        verify(mcp, never()).call(eq("get_org_context"), anyMap(), any(), anyString(), any());
        verify(llm, never()).complete(any());
    }

    @Test void wrongOrganizationContextIsNotIncorporated() {
        allow("admin.sessions.diagnose", "support.context.get_org_context"); evidence(id, Instant.now());
        when(mcp.call(eq("get_session_evidence"), anyMap(), eq(identity), anyString(), any()))
                .thenReturn(mapper.valueToTree(Map.of("sessionId", id, "collectedAt", Instant.now().toString(),
                        "facts", List.of("verified session fact"), "gaps", List.of(), "organizationContext",
                        Map.of("sessionId", UUID.randomUUID().toString(), "locationId", "wrong-customer"))));
        var report = service.collect("Diagnose", context, "Bearer token", identity);
        assertThat(report.toInvestigationText()).doesNotContain("wrong-customer");
        assertThat(report.gaps().toString()).contains("Stored organization context was not included");
        verify(mcp, never()).call(eq("get_org_context"), anyMap(), any(), anyString(), any());
    }

    @Test void timeoutAndDeniedEvidenceRemainDistinctAndNeverReachPlanner() {
        allow("*");
        for (var reason : SupportMcpClient.FailureReason.values()) {
            doThrow(new SupportMcpClient.EvidenceFailure(reason)).when(mcp)
                    .call(eq("get_session_evidence"), anyMap(), eq(identity), anyString(), any());
            var report = service.collect("Diagnose", context, "Bearer token", identity);
            assertThat(report.facts()).isEmpty();
            if (reason == SupportMcpClient.FailureReason.TIMEOUT)
                assertThat(report.gaps().toString()).contains("timed out").doesNotContain("access was denied");
            if (reason == SupportMcpClient.FailureReason.ACCESS_DENIED)
                assertThat(report.gaps().toString()).contains("access was denied").doesNotContain("timed out");
        }
        verifyNoInteractions(llm);
    }

    @Test void evidenceBudgetOutlastsGatewayAndOrganizationIsNotReadTwice() {
        allow("admin.sessions.diagnose", "support.context.get_org_context"); evidence(id, Instant.now());
        service.collect("Diagnose", context, "Bearer token", identity);
        var timeout = org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        verify(mcp).call(eq("get_session_evidence"), anyMap(), any(), anyString(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(java.time.Duration.ofSeconds(16));
        verify(mcp, never()).call(eq("get_org_context"), anyMap(), any(), anyString(), any());
    }

    @Test void knowledgeModesNeverQueryWalletOrSessionEvenWithDiagnosisKeywords() {
        for (String mode : List.of("KNOWLEDGE", "CHANGE_PRECHECK")) {
            var guidance = new ContextPayload("charging-sessions", "session", id, null, null, null, id, "admin", Map.of("responseMode", mode));
            assertThat(new DiagnosticIntentRouter().route("Explain payment, meter values, billing and session stuck status", guidance)).isEmpty();
        }
    }
}
