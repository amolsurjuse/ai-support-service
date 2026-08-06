package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AdminMutationService {
    private final AdminMutationPlanner planner;
    private final AdminMutationApprovalStore approvals;
    private final AdminMutationToolClient client;
    private final AiToolAuthorizationService authorizationService;
    private final AiAuditService auditService;
    private final TenantAiPolicyService tenantPolicyService;

    public AdminMutationService(AdminMutationPlanner planner,
                                AdminMutationApprovalStore approvals,
                                AdminMutationToolClient client,
                                AiToolAuthorizationService authorizationService,
                                AiAuditService auditService,
                                TenantAiPolicyService tenantPolicyService) {
        this.planner = planner;
        this.approvals = approvals;
        this.client = client;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.tenantPolicyService = tenantPolicyService;
    }

    Optional<DiagnosticAnswerService.DiagnosticAnswer> answer(String message,
                                                               String authorization,
                                                               IdentityContext identity) {
        Optional<AdminMutationPlanner.MutationCommand> parsed = planner.parse(message);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        if (!tenantPolicyService.policyFor(identity.tenantId()).allowsTool("admin.session.stop")) {
            return Optional.of(answer("admin.mutation.forbidden-by-tenant",
                    "This AI action is not enabled for your tenant. No action was taken."));
        }
        if (!authorizationService.canExecuteAdminMutation(identity)) {
            return Optional.of(answer("admin.mutation.forbidden",
                    "Your administrative role is read-only for AI commands. No action was taken."));
        }
        AdminMutationPlanner.MutationCommand command = parsed.get();
        return command.confirmation()
                ? Optional.of(confirm(command, authorization, identity))
                : Optional.of(propose(command, identity));
    }

    private DiagnosticAnswerService.DiagnosticAnswer propose(AdminMutationPlanner.MutationCommand command,
                                                              IdentityContext identity) {
        var approval = approvals.create(identity, command.operation(), command.targetId());
        auditService.adminMutation(identity, "admin.session.stop", approval.confirmationId(), "PROPOSED");
        return answer("admin.mutation.proposed",
                "Prepared a request to stop charging session %s. No action has been taken. To execute it once, type `confirm %s` before %s."
                        .formatted(command.targetId(), approval.confirmationId(), approval.expiresAt()));
    }

    private DiagnosticAnswerService.DiagnosticAnswer confirm(AdminMutationPlanner.MutationCommand command,
                                                              String authorization,
                                                              IdentityContext identity) {
        Optional<AdminMutationApprovalStore.PendingApproval> acquired = approvals.acquire(identity, command.confirmationId());
        if (acquired.isEmpty()) {
            auditService.adminMutation(identity, "admin.mutation.confirm", command.confirmationId(), "REJECTED");
            return answer("admin.mutation.confirmation-rejected",
                    "That confirmation is expired, belongs to another administrator or tenant, or was already used. No action was taken.");
        }
        var approval = acquired.get();
        try {
            client.execute(approval, authorization);
            approvals.complete(approval, true);
            auditService.adminMutation(identity, "admin.session.stop", approval.confirmationId(), "SUCCEEDED");
            return answer("admin.session.stop.succeeded",
                    "The stop request for charging session %s was accepted. Confirmation %s cannot be reused."
                            .formatted(approval.targetId(), approval.confirmationId()));
        } catch (RuntimeException ex) {
            approvals.complete(approval, false);
            auditService.adminMutation(identity, "admin.session.stop", approval.confirmationId(), "OUTCOME_UNKNOWN");
            return answer("admin.session.stop.outcome-unknown",
                    "The stop request did not return a confirmed result. For safety, this confirmation cannot be retried. Check the session state before taking another action.");
        }
    }

    private static DiagnosticAnswerService.DiagnosticAnswer answer(String tool, String text) {
        return new DiagnosticAnswerService.DiagnosticAnswer(tool, text, "");
    }
}
