package com.electrahub.aisupport.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedIdentityContextResolverTest {
    private static final String SECRET = "tenant-context-test-secret";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TrustedIdentityContextResolver resolver =
            new TrustedIdentityContextResolver(objectMapper, SECRET, true, "test");

    @Test
    void rejectsPlaceholderSecretInProduction() {
        assertThatThrownBy(() -> new TrustedIdentityContextResolver(
                objectMapper, "CHANGE_ME", true, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_INTERNAL_ACCESS_CONTEXT_SECRET");
    }

    @Test
    void resolvesValidSignedGatewayIdentity() throws Exception {
        MockHttpServletRequest request = signedRequest("tenant-a", "user-a", Instant.now().plusSeconds(30));

        TrustedIdentityContextResolver.IdentityContext identity = resolver.resolve(request);

        assertThat(identity.tenantId()).isEqualTo("tenant-a");
        assertThat(identity.userId()).isEqualTo("user-a");
        assertThat(identity.roles()).containsExactlyInAnyOrder("DRIVER", "TENANT_ADMIN");
        assertThat(identity.authenticated()).isTrue();
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        MockHttpServletRequest request = signedRequest("tenant-a", "user-a", Instant.now().plusSeconds(30));
        request.removeHeader(TrustedIdentityContextResolver.SIGNATURE_HEADER);
        request.addHeader(TrustedIdentityContextResolver.SIGNATURE_HEADER, "invalid-signature");

        assertUnauthorized(request);
    }

    @Test
    void rejectsExpiredIdentity() throws Exception {
        assertUnauthorized(signedRequest("tenant-a", "user-a", Instant.now().minusSeconds(1)));
    }

    @Test
    void rejectsDirectHeaderThatDoesNotMatchSignedTenant() throws Exception {
        MockHttpServletRequest request = signedRequest("tenant-a", "user-a", Instant.now().plusSeconds(30));
        request.removeHeader(TrustedIdentityContextResolver.TENANT_HEADER);
        request.addHeader(TrustedIdentityContextResolver.TENANT_HEADER, "tenant-b");

        assertUnauthorized(request);
    }

    @Test
    void rejectsBearerAuthenticationWithoutGatewayIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");

        assertUnauthorized(request);
    }

    @Test
    void permitsAnonymousRequestsInPublicTenant() {
        TrustedIdentityContextResolver.IdentityContext identity = resolver.resolve(new MockHttpServletRequest());

        assertThat(identity.tenantId()).isEqualTo("public");
        assertThat(identity.userId()).isEqualTo("anonymous");
        assertThat(identity.authenticated()).isFalse();
    }

    private MockHttpServletRequest signedRequest(String tenantId, String userId, Instant expiresAt) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "version", 1,
                "userId", userId,
                "tenantId", tenantId,
                "roles", List.of("driver", "TENANT_ADMIN"),
                "expiresAt", expiresAt.toEpochMilli()));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        request.addHeader(TrustedIdentityContextResolver.USER_HEADER, userId);
        request.addHeader(TrustedIdentityContextResolver.TENANT_HEADER, tenantId);
        request.addHeader(TrustedIdentityContextResolver.CONTEXT_HEADER, payload);
        request.addHeader(TrustedIdentityContextResolver.SIGNATURE_HEADER, signature(payload));
        return request;
    }

    private String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    }

    private void assertUnauthorized(MockHttpServletRequest request) {
        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(401);
    }
}
