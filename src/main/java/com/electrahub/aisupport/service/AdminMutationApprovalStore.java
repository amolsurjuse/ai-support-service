package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AdminMutationApprovalStore {
    private final ConcurrentMap<UUID, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public AdminMutationApprovalStore(@Value("${electrahub.ai-support.admin-approval-ttl-ms:300000}") long ttlMs) {
        this(Duration.ofMillis(ttlMs), Clock.systemUTC());
    }

    AdminMutationApprovalStore(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public PendingApproval create(IdentityContext identity,
                                  AdminMutationPlanner.Operation operation,
                                  UUID targetId) {
        cleanupExpired();
        UUID confirmationId = UUID.randomUUID();
        PendingApproval approval = new PendingApproval(
                confirmationId, identity.tenantId(), identity.userId(), operation, targetId,
                confirmationId.toString(), clock.instant().plus(ttl));
        approvals.put(confirmationId, approval);
        return approval;
    }

    public Optional<PendingApproval> acquire(IdentityContext identity, UUID confirmationId) {
        PendingApproval approval = approvals.get(confirmationId);
        if (approval == null || approval.expiresAt().isBefore(clock.instant())) {
            approvals.remove(confirmationId);
            return Optional.empty();
        }
        if (!approval.tenantId().equals(identity.tenantId()) || !approval.userId().equals(identity.userId())) {
            return Optional.empty();
        }
        return approval.state.compareAndSet(State.PENDING, State.EXECUTING)
                ? Optional.of(approval) : Optional.empty();
    }

    public void complete(PendingApproval approval, boolean success) {
        approval.state.set(success ? State.SUCCEEDED : State.OUTCOME_UNKNOWN);
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        approvals.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    enum State { PENDING, EXECUTING, SUCCEEDED, OUTCOME_UNKNOWN }

    public static final class PendingApproval {
        private final UUID confirmationId;
        private final String tenantId;
        private final String userId;
        private final AdminMutationPlanner.Operation operation;
        private final UUID targetId;
        private final String idempotencyKey;
        private final Instant expiresAt;
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

        PendingApproval(UUID confirmationId, String tenantId, String userId,
                        AdminMutationPlanner.Operation operation, UUID targetId,
                        String idempotencyKey, Instant expiresAt) {
            this.confirmationId = confirmationId;
            this.tenantId = tenantId;
            this.userId = userId;
            this.operation = operation;
            this.targetId = targetId;
            this.idempotencyKey = idempotencyKey;
            this.expiresAt = expiresAt;
        }

        public UUID confirmationId() { return confirmationId; }
        public String tenantId() { return tenantId; }
        public String userId() { return userId; }
        public AdminMutationPlanner.Operation operation() { return operation; }
        public UUID targetId() { return targetId; }
        public String idempotencyKey() { return idempotencyKey; }
        public Instant expiresAt() { return expiresAt; }
    }
}
