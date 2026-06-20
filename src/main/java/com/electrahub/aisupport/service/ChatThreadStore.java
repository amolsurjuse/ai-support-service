package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChatThreadStore {
    private final ConcurrentHashMap<UUID, PendingMessage> pendingMessages = new ConcurrentHashMap<>();

    public PendingMessage create(UUID requestedThreadId, String content, ContextPayload context) {
        UUID threadId = requestedThreadId == null ? UUID.randomUUID() : requestedThreadId;
        UUID messageId = UUID.randomUUID();
        PendingMessage pending = new PendingMessage(threadId, messageId, content, context, Instant.now());
        pendingMessages.put(messageId, pending);
        return pending;
    }

    public Optional<PendingMessage> find(UUID messageId) {
        return Optional.ofNullable(pendingMessages.get(messageId));
    }

    public record PendingMessage(
            UUID threadId,
            UUID messageId,
            String content,
            ContextPayload context,
            Instant createdAt
    ) {
    }
}
