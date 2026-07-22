package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.config.AiSupportProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChatThreadStore {
    private static final Duration MINIMUM_TTL = Duration.ofMinutes(1);
    private final ConcurrentHashMap<UUID, StoredMessage> messages = new ConcurrentHashMap<>();
    private final Duration ttl;

    ChatThreadStore(AiSupportProperties properties) {
        this.ttl = Duration.ofMillis(Math.max(MINIMUM_TTL.toMillis(), properties.threadTtlMs()));
    }

    public PendingMessage create(UUID requestedThreadId, String content, ContextPayload context) {
        purgeExpired();
        UUID threadId = requestedThreadId == null ? UUID.randomUUID() : requestedThreadId;
        UUID messageId = UUID.randomUUID();
        PendingMessage pending = new PendingMessage(threadId, messageId, content, context, Instant.now());
        messages.put(messageId, new StoredMessage(pending, null));
        return pending;
    }

    public Optional<StoredMessage> find(UUID messageId) {
        purgeExpired();
        return Optional.ofNullable(messages.get(messageId));
    }

    public Optional<StoredMessage> complete(UUID messageId, CompletedAnswer answer) {
        purgeExpired();
        StoredMessage updated = messages.computeIfPresent(messageId, (ignored, current) ->
                new StoredMessage(current.pending(), answer));
        return Optional.ofNullable(updated);
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        messages.entrySet().removeIf(entry -> entry.getValue().pending().createdAt().isBefore(cutoff));
    }

    public record PendingMessage(
            UUID threadId,
            UUID messageId,
            String content,
            ContextPayload context,
            Instant createdAt
    ) {
    }

    public record StoredMessage(PendingMessage pending, CompletedAnswer answer) {
    }

    public record CompletedAnswer(
            String text,
            String tool,
            String contextSummary,
            int latencyMs
    ) {
    }
}
