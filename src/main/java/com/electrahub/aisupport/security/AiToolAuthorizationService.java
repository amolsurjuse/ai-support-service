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
    private static final Set<String> ADMIN_ROLES = Set.of(
            "SYSTEM_ADMIN", "TENANT_ADMIN", "ENTERPRISE_ADMIN", "NETWORK_ADMIN", "LOCATION_ADMIN", "SUPPORT");

    public void requireAudienceAccess(IdentityContext identity, ContextPayload context) {
        if (isAdministrativeAudience(context) && !isAdministrator(identity)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Administrative AI requests require an authorized administrator role.");
        }
    }

    public boolean canRunDiagnostic(IdentityContext identity, ContextPayload context, String diagnostic) {
        requireAudienceAccess(identity, context);
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
        return identity != null && identity.roles().stream()
                .map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLES::contains);
    }

    private static boolean isAdministrativeAudience(ContextPayload context) {
        if (context == null || context.audience() == null) {
            return false;
        }
        String audience = context.audience().trim().toLowerCase(Locale.ROOT);
        return audience.contains("admin") || audience.contains("support") || audience.contains("csr");
    }
}
