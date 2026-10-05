package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.model.ChatDtos.StreamEvent;
import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.electrahub.aisupport.security.AiToolAuthorizationService;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.stereotype.Component;

@Component
public class ChatThreadStore {
    private static final AiToolAuthorizationService AUTHORIZATION = new AiToolAuthorizationService();
    private static final Duration MINIMUM_TTL = Duration.ofMinutes(1);
    private final ConcurrentHashMap<UUID, MessageState> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ThreadOwner> threadOwners = new ConcurrentHashMap<>();
    private final Duration ttl;

    ChatThreadStore(AiSupportProperties properties) {
        this.ttl = Duration.ofMillis(Math.max(MINIMUM_TTL.toMillis(), properties.threadTtlMs()));
    }

    public PendingMessage create(IdentityContext identity, UUID requestedThreadId, String content, ContextPayload context) {
        AUTHORIZATION.requireAnalysisRequestAccess(identity, context, content);
        purgeExpired();
        UUID threadId = requestedThreadId == null ? UUID.randomUUID() : requestedThreadId;
        ThreadOwner owner = new ThreadOwner(identity.tenantId(), identity.userId());
        ThreadOwner existingOwner = threadOwners.putIfAbsent(threadId, owner);
        if (existingOwner != null && !existingOwner.equals(owner)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "The chat thread belongs to a different tenant or user.");
        }
        UUID messageId = UUID.randomUUID();
        PendingMessage pending = new PendingMessage(
                threadId, messageId, identity.tenantId(), identity.userId(), content, context, Instant.now());
        boolean analysis = AUTHORIZATION.isSupportAnalysisRequest(context, content)
                || (AUTHORIZATION.canAnalyzeSupport(identity) && context != null && !context.driverAudience()
                    && context.sessionId() != null && !context.sessionId().isBlank());
        messages.put(messageId, new MessageState(pending, analysis));
        return pending;
    }

    public Optional<StoredMessage> find(IdentityContext identity, UUID messageId) {
        purgeExpired();
        MessageState state = messages.get(messageId);
        return state == null || !state.ownedBy(identity) ? Optional.empty() : Optional.of(state.snapshot());
    }

    public Optional<StoredMessage> complete(IdentityContext identity, UUID messageId, CompletedAnswer answer) {
        purgeExpired();
        MessageState state = messages.get(messageId);
        if (state == null || !state.ownedBy(identity)) {
            return Optional.empty();
        }
        state.complete(answer);
        return Optional.of(state.snapshot());
    }

    public boolean publish(IdentityContext identity, UUID messageId, StreamEvent event) {
        MessageState state = messages.get(messageId);
        if (state == null || !state.ownedBy(identity)) {
            return false;
        }
        state.publish(event);
        return true;
    }

    public List<StreamEvent> awaitEvents(IdentityContext identity, UUID messageId, int offset, Duration timeout)
            throws InterruptedException {
        MessageState state = messages.get(messageId);
        return state == null || !state.ownedBy(identity) ? List.of() : state.awaitEvents(offset, timeout);
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        messages.entrySet().removeIf(entry -> entry.getValue().pending.createdAt().isBefore(cutoff));
        var activeThreads = messages.values().stream().map(state -> state.pending.threadId()).collect(java.util.stream.Collectors.toSet());
        threadOwners.keySet().removeIf(threadId -> !activeThreads.contains(threadId));
    }

    public record PendingMessage(
            UUID threadId,
            UUID messageId,
            String tenantId,
            String userId,
            String content,
            ContextPayload context,
            Instant createdAt
    ) {
    }

    private record ThreadOwner(String tenantId, String userId) {
    }

    public record StoredMessage(PendingMessage pending, CompletedAnswer answer, List<StreamEvent> events) {
    }

    public record CompletedAnswer(
            String text,
            String tool,
            String contextSummary,
            int latencyMs
    ) {
    }

    private static final class MessageState {
        private final PendingMessage pending;
        private final List<StreamEvent> events = new ArrayList<>();
        private CompletedAnswer answer;
        private volatile boolean analysis;

        private MessageState(PendingMessage pending, boolean analysis) {
            this.pending = pending;
            this.analysis = analysis;
        }

        private synchronized void complete(CompletedAnswer completedAnswer) {
            if ("diagnose_support_session".equals(completedAnswer.tool())) analysis = true;
            this.answer = completedAnswer;
            notifyAll();
        }

        private synchronized void publish(StreamEvent event) {
            events.add(event);
            notifyAll();
        }

        private synchronized List<StreamEvent> awaitEvents(int offset, Duration timeout) throws InterruptedException {
            if (events.size() <= offset) {
                wait(Math.max(1L, timeout.toMillis()));
            }
            if (events.size() <= offset) {
                return List.of();
            }
            return List.copyOf(events.subList(offset, events.size()));
        }

        private synchronized StoredMessage snapshot() {
            return new StoredMessage(pending, answer, List.copyOf(events));
        }

        private boolean ownedBy(IdentityContext identity) {
            return identity != null && pending.tenantId().equals(identity.tenantId())
                    && pending.userId().equals(identity.userId())
                    && (!analysis || AUTHORIZATION.canAnalyzeSupport(identity));
        }
    }
}
