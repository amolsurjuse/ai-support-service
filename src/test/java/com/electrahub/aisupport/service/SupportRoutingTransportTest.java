package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SupportRoutingTransportTest {
    private final String id = "c3a62c5e-20f1-4d04-9209-55f3d63a59da";
    private final IdentityContext identity = new IdentityContext("tenant", "agent", Set.of("SUPPORT"), true);
    private ContextPayload context(Map<String, String> attributes) {
        return new ContextPayload("charging-sessions", "session", id, "private-charger", null, null, id, "support", attributes);
    }
    private TenantAiPolicyService policies() {
        var policies = mock(TenantAiPolicyService.class);
        when(policies.policyFor("tenant")).thenReturn(new TenantAiPolicyService.TenantPolicy(
                "tenant", true, 1, 1, 1, List.of(), Set.of("admin.sessions.diagnose", "support.routing.jev")));
        return policies;
    }

    @Test
    void selectedSessionUsesGatewayBearerAndRejectsAnotherSessionsEvidence() {
        var builder = RestClient.builder().baseUrl("https://gateway.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SupportSessionDiagnosticsClient(builder.build(), new AiToolAuthorizationService(), policies());
        server.expect(requestTo("https://gateway.test/session/api/v1/sessions/admin/" + id + "/diagnostics"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer agent-token"))
                .andRespond(withSuccess("{\"sessionId\":\"" + id + "\",\"collectedAt\":\"now\",\"facts\":[\"start requested\"],\"gaps\":[\"tax missing\"]}", MediaType.APPLICATION_JSON));
        var report = client.collect(context(Map.of()), "Bearer agent-token", identity);
        assertThat(report.facts().toString()).contains("start requested");
        assertThat(report.gaps()).contains("tax missing");
        server.verify();
        server.reset();
        server.expect(requestTo("https://gateway.test/session/api/v1/sessions/admin/" + id + "/diagnostics"))
                .andRespond(withSuccess("{\"sessionId\":\"00000000-0000-0000-0000-000000000001\",\"facts\":[\"other customer\"],\"gaps\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.collect(context(Map.of()), "Bearer agent-token", identity).facts()).isEmpty();
        server.verify();
    }

    @Test
    void jevUsesTypedHttpEvaluationWithRedactedQuestionAndPreservesSelectedIdentity() {
        var builder = RestClient.builder().baseUrl("https://ai-gateway.vercel.sh");
        var server = MockRestServiceServer.bindTo(builder).build();
        var router = new JevSupportRouter(builder.build(), true, "provider-test-key", new AiToolAuthorizationService(), policies());
        server.expect(requestTo("https://ai-gateway.vercel.sh/v1/evaluate"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer provider-test-key"))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("typesafe-ai/jev", "zeroDataRetention", "selectedChargingSession")
                            .doesNotContain(id, "private-charger", "customer@example.com", "4111111111111111", "agent-secret");
                })
                .andRespond(withSuccess(answer("session_investigation", "0.92", "0.04", "0.04"), MediaType.APPLICATION_JSON));
        var routed = router.route("Please look into this for customer@example.com " + id + " card 4111111111111111 Bearer agent-secret", context(Map.of()), identity);
        assertThat(routed.attributes()).containsEntry("responseMode", "SELECTED_RECORD");
        assertThat(routed.sessionId()).isEqualTo(id);
        server.verify();
    }

    @Test
    void jevNeverOverridesExplicitModesAndFallsBackOnOutageOrAmbiguity() {
        var builder = RestClient.builder().baseUrl("https://ai-gateway.vercel.sh");
        var server = MockRestServiceServer.bindTo(builder).build();
        var router = new JevSupportRouter(builder.build(), true, "provider-test-key", new AiToolAuthorizationService(), policies());
        for (String mode : List.of("SELECTED_RECORD", "KNOWLEDGE", "CHANGE_PRECHECK")) {
            var explicit = context(Map.of("responseMode", mode));
            assertThat(router.route("Help with this", explicit, identity)).isSameAs(explicit);
        }
        var original = context(Map.of());
        server.expect(requestTo("https://ai-gateway.vercel.sh/v1/evaluate")).andRespond(withServerError());
        assertThat(router.route("Help with this", original, identity)).isSameAs(original);
        server.verify();
        var mapper = JsonMapper.builder().build();
        assertThat(JevSupportRouter.acceptedChoice(mapper.readTree(answer("session_investigation", "0.6", "0.3", "0.1")))).isEqualTo("unchanged");
        assertThat(JevSupportRouter.acceptedChoice(mapper.readTree(answer("execute_refund", "0.9", "0.05", "0.05")))).isEqualTo("unchanged");
        assertThat(JevSupportRouter.acceptedChoice(mapper.readTree(answer("session_investigation", "1.5", "0", "0")))).isEqualTo("unchanged");
    }

    @Test
    void jevPolicyAndDriverIdentityPreventProviderCalls() {
        var client = mock(RestClient.class);
        var policies = policies();
        var router = new JevSupportRouter(client, true, "key", new AiToolAuthorizationService(), policies);
        var original = context(Map.of());
        assertThat(router.route("Help with this", original, new IdentityContext("tenant", "driver", Set.of(), true))).isSameAs(original);
        when(policies.policyFor("tenant")).thenReturn(new TenantAiPolicyService.TenantPolicy("tenant", true, 1, 1, 1, List.of(), Set.of("admin.sessions.diagnose")));
        assertThat(router.route("Help with this", original, identity)).isSameAs(original);
        verifyNoInteractions(client);
    }

    private String answer(String choice, String investigation, String knowledge, String unchanged) {
        return "{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"" + choice
                + "\",\"probabilities\":{\"session_investigation\":" + investigation
                + ",\"knowledge\":" + knowledge + ",\"unchanged\":" + unchanged + "}}}}";
    }
}
