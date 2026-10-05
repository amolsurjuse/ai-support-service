package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.*;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in contract with a locally started MCP jar and synthetic scoped gateway fixture. */
@EnabledIfSystemProperty(named = "support.mcp.contract-url", matches = "http://127\\.0\\.0\\.1:[0-9]+/mcp")
class SupportMcpLiveContractTest {
    @Test void realServerAndHostProduceScopedEvidenceAndVersionedContext() {
        var mapper = JsonMapper.builder().build();
        var access = new AiToolAuthorizationService();
        var client = new SupportMcpClient(true, System.getProperty("support.mcp.contract-url"), mapper,
                new TrustedIdentityContextSigner(mapper, "synthetic-contract-test-shared-secret-32"), access, new AiAuditService());
        var policies = mock(TenantAiPolicyService.class);
        when(policies.policyFor("test-tenant")).thenReturn(new TenantAiPolicyService.TenantPolicy(
                "test-tenant", true, 1, 1, 1, List.of(), Set.of("*")));
        var service = new SupportMcpInvestigationService(client, access, policies, mock(LlmClient.class), mapper, new PiiRedactor(), false);
        String id = "c3a62c5e-20f1-4d04-9209-55f3d63a59da";
        var context = new ContextPayload("charging-sessions", "session", id, null, null, null, id, "support", Map.of("responseMode", "SELECTED_RECORD"));
        try {
            var report = service.collect("Diagnose selected session", context, "Bearer synthetic-agent-token",
                    new IdentityContext("test-tenant", "test-agent", Set.of("SUPPORT"), true));
            assertThat(report.facts()).contains("Lifecycle assessment: start phase incomplete", "Authorization recorded: 20 EUR; capture unconfirmed");
            assertThat(report.references()).hasSize(3);
            assertThat(String.join("\n", report.references())).contains("repositoryCatalog", "synthetic-location", "get_service_topology");
            assertThat(report.gaps()).containsExactly("Synthetic gateway fixture: physical-start evidence unavailable");
            assertThatThrownBy(() -> service.collect("Diagnose", context, "Bearer synthetic-agent-token",
                    new IdentityContext("test-tenant", "test-agent", Set.of("ADMIN_READ_ONLY"), true))).hasMessageContaining("403");
        } finally { service.close(); }
    }
}
