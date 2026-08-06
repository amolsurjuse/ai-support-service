package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.model.ChatDtos.StreamEvent;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatThreadStoreTenantIsolationTest {
    private final AiSupportProperties properties = mock(AiSupportProperties.class);
    private final ChatThreadStore store = store();

    @Test
    void isolatesMessagesAndEventsByTenantAndUser() throws Exception {
        IdentityContext owner = identity("tenant-a", "user-a");
        IdentityContext otherTenant = identity("tenant-b", "user-a");
        IdentityContext otherUser = identity("tenant-a", "user-b");
        ChatThreadStore.PendingMessage message = store.create(owner, null, "Help", context());
        StreamEvent token = StreamEvent.token(message.messageId(), "Hello");

        assertThat(store.publish(owner, message.messageId(), token)).isTrue();
        assertThat(store.find(owner, message.messageId())).isPresent();
        assertThat(store.awaitEvents(owner, message.messageId(), 0, Duration.ofMillis(1))).containsExactly(token);

        assertThat(store.find(otherTenant, message.messageId())).isEmpty();
        assertThat(store.find(otherUser, message.messageId())).isEmpty();
        assertThat(store.publish(otherTenant, message.messageId(), token)).isFalse();
        assertThat(store.complete(otherUser, message.messageId(), answer())).isEmpty();
        assertThat(store.awaitEvents(otherTenant, message.messageId(), 0, Duration.ofMillis(1))).isEmpty();
    }

    @Test
    void preventsThreadIdReuseAcrossTenantOrUserBoundaries() {
        UUID threadId = UUID.randomUUID();
        store.create(identity("tenant-a", "user-a"), threadId, "First", context());

        assertForbidden(() -> store.create(identity("tenant-b", "user-a"), threadId, "Second", context()));
        assertForbidden(() -> store.create(identity("tenant-a", "user-b"), threadId, "Third", context()));
        assertThat(store.create(identity("tenant-a", "user-a"), threadId, "Fourth", context()).threadId())
                .isEqualTo(threadId);
    }

    private ChatThreadStore store() {
        when(properties.threadTtlMs()).thenReturn(900_000L);
        return new ChatThreadStore(properties);
    }

    private static IdentityContext identity(String tenantId, String userId) {
        return new IdentityContext(tenantId, userId, Set.of("DRIVER"), true);
    }

    private static ContextPayload context() {
        return new ContextPayload("map", "charger", null, "charger-1", "connector-1", "location-1", null, "driver");
    }

    private static ChatThreadStore.CompletedAnswer answer() {
        return new ChatThreadStore.CompletedAnswer("Done", "none", "tenant-safe", 10);
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(403);
    }
}
