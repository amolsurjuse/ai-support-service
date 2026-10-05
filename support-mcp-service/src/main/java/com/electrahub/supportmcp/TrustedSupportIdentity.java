package com.electrahub.supportmcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** A private gateway/AI-host trust contract; not public OAuth or a customer credential store. */
@Component
public class TrustedSupportIdentity extends OncePerRequestFilter {
    static final String ATTRIBUTE = TrustedSupportIdentity.class.getName();
    static final String CONTEXT = "X-ElectraHub-Identity-Context";
    static final String SIGNATURE = "X-ElectraHub-Identity-Context-Signature";
    private final ObjectMapper mapper;
    private final byte[] secret;

    public TrustedSupportIdentity(ObjectMapper mapper, @Value("${support-mcp.identity-secret:}") String secret) {
        if (secret == null || secret.length() < 32 || secret.startsWith("CHANGE_ME")
                || secret.equals("electrahub-local-access-context-secret"))
            throw new IllegalStateException("Configure a distinct non-default internal identity secret of at least 32 characters.");
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // Actuator is isolated on its management port. Authenticate all application paths,
        // including path parameters/normalized variants that MVC may map to /mcp.
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // This is server-to-server only. Browsers are never a trusted MCP client.
        if (request.getHeader("Origin") != null) { response.sendError(403); return; }
        try { request.setAttribute(ATTRIBUTE, resolve(request)); }
        catch (Denied denied) { response.sendError(denied.status); return; }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    Identity resolve(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.substring(7).isBlank() || bearer.length() > 8192)
            throw new Denied(401);
        try {
            String payload = request.getHeader(CONTEXT), signature = request.getHeader(SIGNATURE);
            if (payload == null || payload.length() > 8192 || signature == null || signature.length() > 128) throw new Denied(401);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)), Base64.getUrlDecoder().decode(signature)))
                throw new Denied(401);
            var body = mapper.readTree(Base64.getUrlDecoder().decode(payload));
            String tenant = body.path("tenantId").asText(""), user = body.path("userId").asText("");
            long expires = body.path("expiresAt").asLong(0), now = Instant.now().toEpochMilli();
            if (body.path("version").asInt() != 1 || expires <= now || expires > now + 120_000
                    || !tenant.matches("[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}")
                    || !user.matches("[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}")
                    || !tenant.equals(request.getHeader("X-ElectraHub-Tenant-Id"))
                    || !user.equals(request.getHeader("X-ElectraHub-User-Id"))) throw new Denied(401);
            Set<String> roles = new HashSet<>();
            body.path("roles").forEach(role -> { if (role.isTextual()) roles.add(role.asText()); });
            if (!roles.contains("SYSTEM_ADMIN") && !roles.contains("SUPPORT")) throw new Denied(403);
            return new Identity(tenant, user, Set.copyOf(roles), bearer);
        } catch (Denied ex) { throw ex; }
        catch (Exception ex) { throw new Denied(401); }
    }

    record Identity(String tenantId, String userId, Set<String> roles, String bearer) {}
    static class Denied extends RuntimeException { final int status; Denied(int status) { this.status = status; } }
}
