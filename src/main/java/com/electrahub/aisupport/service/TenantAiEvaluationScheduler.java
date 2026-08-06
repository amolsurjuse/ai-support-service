package com.electrahub.aisupport.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TenantAiEvaluationScheduler {
    private static final Logger log = LoggerFactory.getLogger(TenantAiEvaluationScheduler.class);

    private final TenantAiPolicyService policies;
    private final TenantAiQuotaService quotas;
    private final AiAuditService audit;

    public TenantAiEvaluationScheduler(TenantAiPolicyService policies,
                                       TenantAiQuotaService quotas,
                                       AiAuditService audit) {
        this.policies = policies;
        this.quotas = quotas;
        this.audit = audit;
    }

    @Scheduled(
            initialDelayString = "${electrahub.ai-support.multitenancy.evaluation-initial-delay-ms:60000}",
            fixedDelayString = "${electrahub.ai-support.multitenancy.evaluation-interval-ms:900000}")
    void runEvaluation() {
        if (!policies.evaluationEnabled()) {
            return;
        }
        int evaluated = 0;
        try {
            for (String tenantId : policies.evaluationTenants()) {
                var policy = policies.policyFor(tenantId);
                if (!policy.enabled() || policy.requestsPerMinute() < 1
                        || policy.requestsPerDay() < 1 || policy.tokensPerDay() < 1) {
                    throw new IllegalStateException("Tenant AI policy failed evaluation");
                }
                evaluated++;
            }
            if (policies.quotaEnabled() && !"PONG".equalsIgnoreCase(quotas.verifyStore())) {
                throw new IllegalStateException("Redis quota store health check failed");
            }
            audit.tenantEvaluation(evaluated, "PASS");
        } catch (RuntimeException ex) {
            audit.tenantEvaluation(evaluated, "FAIL");
            log.error("Scheduled tenant AI evaluation failed evaluatedTenants={} errorType={}",
                    evaluated, ex.getClass().getSimpleName());
        }
    }
}
