package com.electrahub.aisupport.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class TrustedIdentityContextResolver {
    public static final String USER_HEADER = "X-ElectraHub-User-Id";
    public static final String TENANT_HEADER = "X-ElectraHub-Tenant-Id";
    public static final String CONTEXT_HEADER = "X-ElectraHub-Identity-Context";
    public static final String SIGNATURE_HEADER = "X-ElectraHub-Identity-Context-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String ANONYMOUS_TENANT = "public";
    private static final String ANONYMOUS_USER = "anonymous";

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final boolean requireSignedAuthenticated;

    public TrustedIdentityContextResolver(
            ObjectMapper objectMapper,
            @Value("${app.access-context.secret:electrahub-local-access-context-secret}") String secret,
            @Value("${electrahub.ai-support.tenant-context.require-signed-authenticated:true}")
            boolean requireSignedAuthenticated,
            @Value("${spring.profiles.active:}") String activeProfiles
    ) {
        this.objectMapper = objectMapper;
        if ((secret == null || secret.isBlank() || secret.startsWith("CHANGE_ME"))
                && activeProfiles != null && activeProfiles.toLowerCase().contains("prod")) {
            throw new IllegalStateException("APP_INTERNAL_ACCESS_CONTEXT_SECRET must be configured in production");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.requireSignedAuthenticated = requireSignedAuthenticated;
    }

    public IdentityContext resolve(HttpServletRequest request) {
        String payload = request.getHeader(CONTEXT_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        boolean authenticated = hasText(request.getHeader(HttpHeaders.AUTHORIZATION));

        if (!hasText(payload) && !hasText(signature)) {
            if (authenticated && requireSignedAuthenticated) {
                throw unauthorized("Authenticated AI requests require a trusted gateway identity context.");
            }
            return new IdentityContext(ANONYMOUS_TENANT, ANONYMOUS_USER, Set.of(), false);
        }
        if (!hasText(payload) || !hasText(signature)) {
            throw unauthorized("The trusted gateway identity context is incomplete.");
        }
        verifySignature(payload, signature);

        try {
            JsonNode body = objectMapper.readTree(Base64.getUrlDecoder().decode(payload));
            if (body.path("version").asInt() != 1) {
                throw unauthorized("The trusted gateway identity context version is unsupported.");
            }
            String userId = required(body, "userId");
            String tenantId = required(body, "tenantId");
            long expiresAt = body.path("expiresAt").asLong(0L);
            if (expiresAt <= Instant.now().toEpochMilli()) {
                throw unauthorized("The trusted gateway identity context expired.");
            }
            validateIdentifier("tenant", tenantId, 64);
            validateIdentifier("user", userId, 128);
            if (!matchesHeader(request, USER_HEADER, userId) || !matchesHeader(request, TENANT_HEADER, tenantId)) {
                throw unauthorized("The trusted gateway identity headers did not match their signed context.");
            }

            Set<String> roles = new LinkedHashSet<>();
            JsonNode roleValues = body.path("roles");
            if (roleValues.isArray()) {
                roleValues.forEach(value -> {
                    if (value.isTextual() && !value.asText().isBlank()) {
                        roles.add(value.asText().trim().toUpperCase());
                    }
                });
            }
            return new IdentityContext(tenantId, userId, Set.copyOf(roles), true);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unauthorized("The trusted gateway identity context was invalid.");
        }
    }

    private void verifySignature(String payload, String suppliedSignature) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
            byte[] supplied = Base64.getUrlDecoder().decode(suppliedSignature);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw unauthorized("The trusted gateway identity signature was invalid.");
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unauthorized("The trusted gateway identity signature was invalid.");
        }
    }

    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw unauthorized("The trusted gateway identity context omitted " + field + ".");
        }
        return value;
    }

    private static void validateIdentifier(String label, String value, int maxLength) {
        if (value.length() > maxLength || !value.matches("[A-Za-z0-9][A-Za-z0-9._:@-]*")) {
            throw unauthorized("The trusted gateway " + label + " identifier was invalid.");
        }
    }

    private static boolean matchesHeader(HttpServletRequest request, String name, String expected) {
        String value = request.getHeader(name);
        return hasText(value) && expected.equals(value.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    public record IdentityContext(String tenantId, String userId, Set<String> roles, boolean authenticated) {
    }
}
