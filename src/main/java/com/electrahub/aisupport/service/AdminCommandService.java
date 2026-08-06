package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

@Service
public class AdminCommandService {
    private static final Logger log = LoggerFactory.getLogger(AdminCommandService.class);
    private final AdminCommandPlanner planner;
    private final AdminToolRegistry registry;
    private final AdminReadOnlyToolClient client;
    private final AiToolAuthorizationService authorizationService;
    private final AdminMutationService mutationService;
    private final TenantAiPolicyService tenantPolicyService;

    public AdminCommandService(AdminCommandPlanner planner,
                               AdminToolRegistry registry,
                               AdminReadOnlyToolClient client,
                               AiToolAuthorizationService authorizationService,
                               AdminMutationService mutationService,
                               TenantAiPolicyService tenantPolicyService) {
        this.planner = planner;
        this.registry = registry;
        this.client = client;
        this.authorizationService = authorizationService;
        this.mutationService = mutationService;
        this.tenantPolicyService = tenantPolicyService;
    }

    public Optional<DiagnosticAnswerService.DiagnosticAnswer> answer(String message,
                                                                     ContextPayload context,
                                                                     String authorization,
                                                                     IdentityContext identity) {
        if (!isAdminAudience(context)) {
            return Optional.empty();
        }
        authorizationService.requireAudienceAccess(identity, context);
        Optional<DiagnosticAnswerService.DiagnosticAnswer> mutation = mutationService.answer(
                message, authorization, identity);
        if (mutation.isPresent()) {
            return mutation;
        }
        Optional<AdminCommandPlanner.Plan> planned = planner.plan(message);
        if (planned.isEmpty()) {
            return Optional.empty();
        }
        AdminCommandPlanner.Plan plan = planned.get();
        if (plan.mutation()) {
            return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer(
                    "admin.mutation.requires-approval",
                    "That request would change production data. Admin write commands are not enabled yet; no action was taken.",
                    "tenant-scoped admin policy"));
        }
        AdminToolRegistry.ToolDefinition tool = registry.require(plan.toolId());
        if (!tenantPolicyService.policyFor(identity.tenantId()).allowsTool(tool.auditName())) {
            return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer(
                    "admin.tool.forbidden-by-tenant",
                    "This AI tool is not enabled for your tenant. No query was run.",
                    "tenant AI policy"));
        }
        try {
            var payload = client.execute(tool, plan, authorization);
            return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer(
                    tool.auditName(), client.summarize(tool, payload), tool.description()));
        } catch (RuntimeException ex) {
            log.warn("Tenant-scoped admin tool failed tool={} errorType={}", tool.auditName(), ex.getClass().getSimpleName());
            return Optional.of(new DiagnosticAnswerService.DiagnosticAnswer(
                    tool.auditName() + ".error",
                    "I could not complete that scoped admin query. Please verify your access scope and try again.",
                    tool.description()));
        }
    }

    private static boolean isAdminAudience(ContextPayload context) {
        if (context == null || context.audience() == null) {
            return false;
        }
        String audience = context.audience().toLowerCase(Locale.ROOT);
        return audience.contains("admin") || audience.contains("support") || audience.contains("csr");
    }
}
