package com.electrahub.aisupport.security;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

@Component
public class AiToolAuthorizationService {
    private static final Set<String> SUPPORT_ANALYSIS_ROLES = Set.of("SYSTEM_ADMIN", "SUPPORT");
    private static final Set<String> ADMIN_ROLES = Set.of(
            "SYSTEM_ADMIN", "TENANT_ADMIN", "ENTERPRISE_ADMIN", "NETWORK_ADMIN", "LOCATION_ADMIN", "SUPPORT",
            "ADMIN_READ_ONLY", "ENTERPRISE", "NETWORK", "LOCATION");
    private static final Set<String> ADMIN_MUTATION_ROLES = Set.of(
            "SYSTEM_ADMIN", "TENANT_ADMIN", "ENTERPRISE_ADMIN", "NETWORK_ADMIN", "LOCATION_ADMIN",
            "ENTERPRISE", "NETWORK", "LOCATION");

    public void requireAudienceAccess(IdentityContext identity, ContextPayload context) {
        if (isAdministrativeAudience(context) && !isAdministrator(identity)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Administrative AI requests require an authorized administrator role.");
        }
    }

    public boolean canRunDiagnostic(IdentityContext identity, ContextPayload context, String diagnostic) {
        requireAudienceAccess(identity, context);
        if ("session investigation".equals(diagnostic) || "ocpp history".equals(diagnostic)) {
            return canAnalyzeSupport(identity);
        }
        if (isAdministrativeAudience(context) && isAdministrator(identity)) {
            return true;
        }
        return switch (diagnostic) {
            case "charger" -> true;
            case "payment", "session", "ocpp connection" -> identity != null && identity.authenticated();
            case "ocpp history" -> false;
            default -> false;
        };
    }

    public boolean isAdministrator(IdentityContext identity) {
        return identity != null && identity.authenticated() && identity.roles() != null && identity.roles().stream()
                .map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLES::contains);
    }

    public boolean canAnalyzeSupport(IdentityContext identity) {
        return identity != null && identity.authenticated() && identity.roles() != null && identity.roles().stream()
                .filter(java.util.Objects::nonNull)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .anyMatch(SUPPORT_ANALYSIS_ROLES::contains);
    }

    public void requireSupportAnalysis(IdentityContext identity) {
        if (!canAnalyzeSupport(identity)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Session analysis is available only to authenticated System Admin and Support users.");
        }
    }

    public void requireAnalysisRequestAccess(IdentityContext identity, ContextPayload context, String message) {
        requireAudienceAccess(identity, context);
        if (isSupportAnalysisRequest(context, message)) {
            requireSupportAnalysis(identity);
        }
    }

    /** Client hints select presentation, never grant access to customer evidence. */
    public boolean isSupportAnalysisRequest(ContextPayload context, String message) {
        if (context == null) return false;
        String intent = context.attributes() == null ? "" : context.attributes().getOrDefault("promptIntent", "");
        if ("charging-sessions".equals(context.screen()) && intent != null && Set.of(
                "sessions.overview", "sessions.stuck", "sessions.meter-cost", "sessions.authorization", "sessions.stop-precheck").contains(intent))
            return true;
        String mode = context.attributes() == null ? "" : context.attributes().getOrDefault("responseMode", "");
        mode = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (Set.of("ANALYSIS", "SESSION_ANALYSIS", "SESSION_INVESTIGATION").contains(mode)) return true;
        boolean selectedSession = (context.sessionId() != null && !context.sessionId().isBlank())
                || (context.resourceType() != null && context.resourceType().toLowerCase(Locale.ROOT).contains("session"))
                || (context.screen() != null && context.screen().toLowerCase(Locale.ROOT).contains("session"));
        if ("SELECTED_RECORD".equals(mode) && selectedSession) return true;
        if (Set.of("KNOWLEDGE", "CHANGE_PRECHECK").contains(mode)) return false;
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return isAdministrativeAudience(context) && context.sessionId() != null && !context.sessionId().isBlank()
                && Set.of("diagnos", "investigat", "why", "stuck", "meter", "bill", "authorization", "subscription", "timeline")
                .stream().anyMatch(normalized::contains);
    }

    public boolean canExecuteAdminMutation(IdentityContext identity) {
        return identity != null && identity.authenticated() && identity.roles().stream()
                .map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_MUTATION_ROLES::contains);
    }

    private static boolean isAdministrativeAudience(ContextPayload context) {
        if (context == null || context.audience() == null) {
            return false;
        }
        String audience = context.audience().trim().toLowerCase(Locale.ROOT);
        return audience.contains("admin") || audience.contains("support") || audience.contains("csr");
    }
}
