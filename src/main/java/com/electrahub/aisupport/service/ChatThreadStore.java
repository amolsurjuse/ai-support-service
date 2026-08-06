package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.model.ChatDtos.StreamEvent;
import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;

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
    private static final Duration MINIMUM_TTL = Duration.ofMinutes(1);
    private final ConcurrentHashMap<UUID, MessageState> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ThreadOwner> threadOwners = new ConcurrentHashMap<>();
    private final Duration ttl;

    ChatThreadStore(AiSupportProperties properties) {
        this.ttl = Duration.ofMillis(Math.max(MINIMUM_TTL.toMillis(), properties.threadTtlMs()));
    }

    public PendingMessage create(IdentityContext identity, UUID requestedThreadId, String content, ContextPayload context) {
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
        messages.put(messageId, new MessageState(pending));
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

        private MessageState(PendingMessage pending) {
            this.pending = pending;
        }

        private synchronized void complete(CompletedAnswer completedAnswer) {
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
            return pending.tenantId().equals(identity.tenantId()) && pending.userId().equals(identity.userId());
        }
    }
}
