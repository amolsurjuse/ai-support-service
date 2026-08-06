package com.electrahub.aisupport.security;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiToolAuthorizationServiceTest {
    private final AiToolAuthorizationService authorization = new AiToolAuthorizationService();

    @Test
    void driverCanUseOnlyDriverSafeDiagnostics() {
        IdentityContext driver = identity("tenant-a", "driver-a", "DRIVER", "USER");
        ContextPayload context = context("driver");

        assertThat(authorization.canRunDiagnostic(driver, context, "payment")).isTrue();
        assertThat(authorization.canRunDiagnostic(driver, context, "session")).isTrue();
        assertThat(authorization.canRunDiagnostic(driver, context, "charger")).isTrue();
        assertThat(authorization.canRunDiagnostic(driver, context, "ocpp connection")).isTrue();
        assertThat(authorization.canRunDiagnostic(driver, context, "ocpp history")).isFalse();
    }

    @Test
    void anonymousUserCanOnlyReadPublicChargerDiagnostics() {
        IdentityContext anonymous = new IdentityContext("public", "anonymous", Set.of(), false);
        ContextPayload context = context("driver");

        assertThat(authorization.canRunDiagnostic(anonymous, context, "charger")).isTrue();
        assertThat(authorization.canRunDiagnostic(anonymous, context, "payment")).isFalse();
        assertThat(authorization.canRunDiagnostic(anonymous, context, "session")).isFalse();
        assertThat(authorization.canRunDiagnostic(anonymous, context, "ocpp connection")).isFalse();
    }

    @Test
    void administratorCanUseOperationalDiagnostics() {
        IdentityContext admin = identity("tenant-a", "admin-a", "TENANT_ADMIN", "USER");

        assertThat(authorization.canRunDiagnostic(admin, context("admin"), "ocpp history")).isTrue();
        assertThat(authorization.canRunDiagnostic(admin, context("admin"), "payment")).isTrue();
        assertThat(authorization.canRunDiagnostic(admin, context("driver"), "ocpp history")).isFalse();
    }

    @Test
    void gatewayScopedAdminRolesCanUseAdminAudience() {
        for (String role : Set.of("ADMIN_READ_ONLY", "ENTERPRISE", "NETWORK", "LOCATION")) {
            IdentityContext admin = identity("tenant-a", "admin-a", role, "USER");
            authorization.requireAudienceAccess(admin, context("admin"));
            assertThat(authorization.isAdministrator(admin)).isTrue();
        }
    }

    @Test
    void readOnlyAndSupportRolesCannotExecuteMutations() {
        assertThat(authorization.canExecuteAdminMutation(identity("tenant-a", "admin-a", "ADMIN_READ_ONLY"))).isFalse();
        assertThat(authorization.canExecuteAdminMutation(identity("tenant-a", "support-a", "SUPPORT"))).isFalse();
        assertThat(authorization.canExecuteAdminMutation(identity("tenant-a", "admin-b", "SYSTEM_ADMIN"))).isTrue();
    }

    @Test
    void driverCannotEscalateByChangingAudience() {
        IdentityContext driver = identity("tenant-a", "driver-a", "DRIVER", "USER");

        assertThatThrownBy(() -> authorization.requireAudienceAccess(driver, context("admin")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(403);
    }

    private static IdentityContext identity(String tenant, String user, String... roles) {
        return new IdentityContext(tenant, user, Set.of(roles), true);
    }

    private static ContextPayload context(String audience) {
        return new ContextPayload("map", "charger", null, "charger-1", "connector-1", "location-1", null, audience);
    }
}
