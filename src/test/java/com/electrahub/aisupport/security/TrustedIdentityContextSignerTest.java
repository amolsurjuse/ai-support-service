package com.electrahub.aisupport.security;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedIdentityContextSignerTest {
    private static final String SECRET = "downstream-identity-test-secret";

    @Test
    void signsAContextThatTheResolverAccepts() {
        ObjectMapper objectMapper = new ObjectMapper();
        TrustedIdentityContextSigner signer = new TrustedIdentityContextSigner(objectMapper, SECRET);
        TrustedIdentityContextResolver resolver =
                new TrustedIdentityContextResolver(objectMapper, SECRET, true, "test");
        IdentityContext original = new IdentityContext(
                "tenant-a", "user-a", Set.of("TENANT_ADMIN", "USER"), true);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://service.test/path"));

        signer.apply(builder, original);
        HttpRequest signed = builder.GET().build();
        MockHttpServletRequest downstream = new MockHttpServletRequest();
        signed.headers().map().forEach((name, values) -> values.forEach(value -> downstream.addHeader(name, value)));
        downstream.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");

        IdentityContext verified = resolver.resolve(downstream);
        assertThat(verified.tenantId()).isEqualTo("tenant-a");
        assertThat(verified.userId()).isEqualTo("user-a");
        assertThat(verified.roles()).containsExactlyInAnyOrder("TENANT_ADMIN", "USER");
    }

    @Test
    void doesNotAttachTenantHeadersForAnonymousIdentity() {
        TrustedIdentityContextSigner signer = new TrustedIdentityContextSigner(new ObjectMapper(), SECRET);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://service.test/path"));

        signer.apply(builder, new IdentityContext("public", "anonymous", Set.of(), false));
        HttpRequest request = builder.GET().build();

        assertThat(request.headers().firstValue(TrustedIdentityContextResolver.TENANT_HEADER)).isEmpty();
        assertThat(request.headers().firstValue(TrustedIdentityContextResolver.CONTEXT_HEADER)).isEmpty();
    }
}
