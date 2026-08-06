package com.electrahub.aisupport.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAiEvaluationSchedulerTest {
    @Test
    void validatesEveryConfiguredTenantAndQuotaStore() {
        TenantAiPolicyService policies = mock(TenantAiPolicyService.class);
        TenantAiQuotaService quotas = mock(TenantAiQuotaService.class);
        AiAuditService audit = mock(AiAuditService.class);
        when(policies.evaluationEnabled()).thenReturn(true);
        when(policies.evaluationTenants()).thenReturn(List.of("tenant-a", "tenant-b"));
        when(policies.quotaEnabled()).thenReturn(true);
        when(quotas.verifyStore()).thenReturn("PONG");
        var valid = new TenantAiPolicyService.TenantPolicy(
                "tenant-a", true, 10, 100, 1000, List.of(), Set.of("*"));
        when(policies.policyFor("tenant-a")).thenReturn(valid);
        when(policies.policyFor("tenant-b")).thenReturn(new TenantAiPolicyService.TenantPolicy(
                "tenant-b", true, 20, 200, 2000, List.of(), Set.of("*")));

        new TenantAiEvaluationScheduler(policies, quotas, audit).runEvaluation();

        verify(quotas).verifyStore();
        verify(audit).tenantEvaluation(2, "PASS");
    }

    @Test
    void auditsFailureWithoutThrowingFromScheduler() {
        TenantAiPolicyService policies = mock(TenantAiPolicyService.class);
        TenantAiQuotaService quotas = mock(TenantAiQuotaService.class);
        AiAuditService audit = mock(AiAuditService.class);
        when(policies.evaluationEnabled()).thenReturn(true);
        when(policies.evaluationTenants()).thenReturn(List.of("tenant-disabled"));
        when(policies.policyFor("tenant-disabled")).thenReturn(new TenantAiPolicyService.TenantPolicy(
                "tenant-disabled", false, 10, 100, 1000, List.of(), Set.of()));

        new TenantAiEvaluationScheduler(policies, quotas, audit).runEvaluation();

        verify(audit).tenantEvaluation(0, "FAIL");
    }
}
