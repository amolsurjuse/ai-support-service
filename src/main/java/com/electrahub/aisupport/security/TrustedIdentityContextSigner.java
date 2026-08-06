package com.electrahub.aisupport.security;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TrustedIdentityContextSigner {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public TrustedIdentityContextSigner(
            ObjectMapper objectMapper,
            @Value("${app.access-context.secret:electrahub-local-access-context-secret}") String secret
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void apply(HttpRequest.Builder builder, IdentityContext identity) {
        if (identity == null || !identity.authenticated()) {
            return;
        }
        String payload = payload(identity, Instant.now().plusSeconds(30));
        builder.header(TrustedIdentityContextResolver.USER_HEADER, identity.userId());
        builder.header(TrustedIdentityContextResolver.TENANT_HEADER, identity.tenantId());
        builder.header(TrustedIdentityContextResolver.CONTEXT_HEADER, payload);
        builder.header(TrustedIdentityContextResolver.SIGNATURE_HEADER, signature(payload));
    }

    String payload(IdentityContext identity, Instant expiresAt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", 1);
            body.put("userId", identity.userId());
            body.put("tenantId", identity.tenantId());
            body.put("roles", identity.roles());
            body.put("expiresAt", expiresAt.toEpochMilli());
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(body));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialize the trusted AI identity context", ex);
        }
    }

    String signature(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign the trusted AI identity context", ex);
        }
    }
}
