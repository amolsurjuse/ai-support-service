package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.UUID;

@Component
public class SupportSessionDiagnosticsClient {
    private final RestClient gateway;
    private final AiToolAuthorizationService authorization;
    private final TenantAiPolicyService policies;

    @org.springframework.beans.factory.annotation.Autowired
    public SupportSessionDiagnosticsClient(
            @Value("${electrahub.ai-support.gateway-url:http://api-gateway:8090}") String gatewayUrl,
            AiToolAuthorizationService authorization, TenantAiPolicyService policies) {
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(1000);
        timeouts.setReadTimeout(7500);
        this.gateway = RestClient.builder().baseUrl(gatewayUrl).requestFactory(timeouts).build();
        this.authorization = authorization;
        this.policies = policies;
    }

    SupportSessionDiagnosticsClient(RestClient gateway, AiToolAuthorizationService authorization, TenantAiPolicyService policies) {
        this.gateway = gateway;
        this.authorization = authorization;
        this.policies = policies;
    }

    BackendDiagnosticsClient.DiagnosticsSnapshot collect(ContextPayload context, String bearer, IdentityContext identity) {
        authorization.requireSupportAnalysis(identity);
        var policy = policies.policyFor(identity.tenantId());
        if (!policy.enabled() || !policy.allowsTool("admin.sessions.diagnose"))
            return gap("Selected-session investigation is not permitted by your role or tenant policy.");
        if (bearer == null || !bearer.regionMatches(true, 0, "Bearer ", 0, 7))
            return gap("Selected-session investigation needs an authenticated support request.");
        try {
            UUID id = UUID.fromString(context.sessionId());
            // The gateway derives and signs data scope from the agent's token. Never use direct/internal session APIs.
            Report report = gateway.get().uri("/session/api/v1/sessions/admin/{id}/diagnostics", id)
                    .header(HttpHeaders.AUTHORIZATION, bearer).retrieve().body(Report.class);
            if (report == null || !id.equals(report.sessionId()) || report.facts() == null || report.gaps() == null)
                return gap("Selected-session diagnostic response was incomplete or did not match the selected session.");
            var facts = new java.util.ArrayList<String>();
            facts.add("Evidence collected at " + report.collectedAt() + " for selected session " + id);
            facts.addAll(report.facts());
            return new BackendDiagnosticsClient.DiagnosticsSnapshot(List.copyOf(facts), report.gaps());
        } catch (RuntimeException ex) {
            return gap("Selected-session diagnostics unavailable or access denied. No session failure cause is confirmed; check scoped access and retry.");
        }
    }

    private static BackendDiagnosticsClient.DiagnosticsSnapshot gap(String message) {
        return new BackendDiagnosticsClient.DiagnosticsSnapshot(List.of(), List.of(message));
    }
    public record Report(UUID sessionId, String collectedAt, List<String> facts, List<String> gaps) {}
}
