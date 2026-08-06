package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.TenantAiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIsolationEvaluationTest {
    private static final String POLICIES = """
            {
              "tenant-a": {
                "requestsPerMinute": 7,
                "requestsPerDay": 70,
                "tokensPerDay": 7000,
                "knowledge": ["Tenant A help line is 111."],
                "allowedAdminTools": ["admin.analytics.overview"]
              },
              "tenant-b": {
                "enabled": false,
                "knowledge": ["Tenant B private escalation is blue."],
                "allowedAdminTools": []
              }
            }
            """;

    @Test
    void selectsOnlyExactTrustedTenantPolicyAndKnowledge() {
        TenantAiPolicyService service = service();

        var tenantA = service.policyFor("tenant-a");
        var tenantB = service.policyFor("tenant-b");

        assertThat(tenantA.enabled()).isTrue();
        assertThat(tenantA.requestsPerMinute()).isEqualTo(7);
        assertThat(tenantA.tokensPerDay()).isEqualTo(7000);
        assertThat(tenantA.knowledgeText()).contains("Tenant A help line").doesNotContain("Tenant B private");
        assertThat(tenantB.enabled()).isFalse();
        assertThat(tenantB.knowledgeText()).contains("Tenant B private").doesNotContain("Tenant A help line");
    }

    @Test
    void tenantToolAllowListIsRestrictiveAndUnknownTenantGetsNoPrivateKnowledge() {
        TenantAiPolicyService service = service();

        assertThat(service.policyFor("tenant-a").allowsTool("admin.analytics.overview")).isTrue();
        assertThat(service.policyFor("tenant-a").allowsTool("admin.users.list")).isFalse();
        assertThat(service.policyFor("tenant-new").knowledge()).isEmpty();
        assertThat(service.policyFor("tenant-new").allowedAdminTools()).containsExactly("*");
    }

    @Test
    void rejectsTenantTraversalAndMalformedPolicyAtStartup() {
        assertThatThrownBy(() -> service().policyFor("../tenant-b"))
                .isInstanceOf(TenantAiAccessException.class);

        TenantAiPolicyService invalid = new TenantAiPolicyService(
                new TenantAiProperties(true, true, true, 60, 5000, 1000000, true, "tenant-a", "{bad-json"),
                new ObjectMapper());
        assertThatThrownBy(invalid::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promptContainsOnlyAlreadySelectedTenantKnowledge() {
        var prompt = new LlmClient.LlmPrompt(
                "How do I get help?", null,
                new DiagnosticAnswerService.DiagnosticAnswer("driver_support_context", "Use support.", ""),
                new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of()),
                service().policyFor("tenant-a").knowledgeText());

        String formatted = LlmPromptFormatter.promptText(prompt);
        assertThat(formatted).contains("Tenant A help line is 111").doesNotContain("Tenant B private escalation");
    }

    private static TenantAiPolicyService service() {
        TenantAiPolicyService service = new TenantAiPolicyService(
                new TenantAiProperties(true, true, true, 60, 5000, 1000000, true, "tenant-a,tenant-b", POLICIES),
                new ObjectMapper());
        service.load();
        return service;
    }
}
