package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMutationApprovalStoreTest {
    private final IdentityContext owner = identity("tenant-a", "admin-a");
    private final UUID target = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void confirmationIsTenantAndUserBoundAndSingleUse() {
        AdminMutationApprovalStore store = new AdminMutationApprovalStore(
                Duration.ofMinutes(5), Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC));
        var approval = store.create(owner, AdminMutationPlanner.Operation.STOP_SESSION, target);

        assertThat(store.acquire(identity("tenant-b", "admin-a"), approval.confirmationId())).isEmpty();
        assertThat(store.acquire(identity("tenant-a", "admin-b"), approval.confirmationId())).isEmpty();
        assertThat(store.acquire(owner, approval.confirmationId())).contains(approval);
        assertThat(store.acquire(owner, approval.confirmationId())).isEmpty();
    }

    @Test
    void expiredConfirmationCannotBeAcquired() {
        AdminMutationApprovalStore store = new AdminMutationApprovalStore(
                Duration.ofSeconds(-1), Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC));
        var approval = store.create(owner, AdminMutationPlanner.Operation.STOP_SESSION, target);
        assertThat(store.acquire(owner, approval.confirmationId())).isEmpty();
    }

    private static IdentityContext identity(String tenant, String user) {
        return new IdentityContext(tenant, user, Set.of("SYSTEM_ADMIN", "USER"), true);
    }
}
