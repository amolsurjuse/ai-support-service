package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AiAuditService {
    private static final Logger audit = LoggerFactory.getLogger("AI_AUDIT");

    public void chatCompleted(IdentityContext identity, UUID threadId, UUID messageId,
                              String tool, int latencyMs, boolean success) {
        audit.info("event=ai_chat tenantId={} userId={} authenticated={} threadId={} messageId={} tool={} latencyMs={} outcome={}",
                safe(identity == null ? null : identity.tenantId()),
                safe(identity == null ? null : identity.userId()),
                identity != null && identity.authenticated(),
                threadId,
                messageId,
                safe(tool),
                latencyMs,
                success ? "SUCCESS" : "FAILURE");
    }

    public void diagnosticCompleted(IdentityContext identity, String diagnostic,
                                    int latencyMs, String outcome) {
        audit.info("event=ai_diagnostic tenantId={} userId={} diagnostic={} latencyMs={} outcome={}",
                safe(identity == null ? null : identity.tenantId()),
                safe(identity == null ? null : identity.userId()),
                safe(diagnostic),
                latencyMs,
                safe(outcome));
    }

    public void adminMutation(IdentityContext identity, String operation,
                              UUID confirmationId, String outcome) {
        audit.info("event=ai_admin_mutation tenantId={} userId={} operation={} confirmationId={} outcome={}",
                safe(identity == null ? null : identity.tenantId()),
                safe(identity == null ? null : identity.userId()),
                safe(operation), confirmationId, safe(outcome));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value.replaceAll("[^A-Za-z0-9._:@-]", "_");
    }
}
