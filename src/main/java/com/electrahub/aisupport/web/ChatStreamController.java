package com.electrahub.aisupport.web;

import com.electrahub.aisupport.model.ChatDtos.StreamEvent;
import com.electrahub.aisupport.service.ChatThreadStore;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
class ChatStreamController {
    private final ChatThreadStore threadStore;
    private final TrustedIdentityContextResolver identityResolver;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ChatStreamController(ChatThreadStore threadStore, TrustedIdentityContextResolver identityResolver) {
        this.threadStore = threadStore;
        this.identityResolver = identityResolver;
    }

    @GetMapping(path = "/threads/{threadId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID threadId,
                      @RequestParam(name = "since") UUID messageId,
                      HttpServletRequest request) {
        IdentityContext identity = identityResolver.resolve(request);
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> streamAnswer(identity, threadId, messageId, emitter));
        return emitter;
    }

    private void streamAnswer(IdentityContext identity, UUID threadId, UUID messageId, SseEmitter emitter) {
        try {
            var stored = threadStore.find(identity, messageId)
                    .filter(message -> message.pending().threadId().equals(threadId))
                    .orElse(null);
            if (stored == null) {
                send(emitter, "error", StreamEvent.error(messageId, "MESSAGE_NOT_FOUND", "I could not find that chat message. Please send it again."));
                emitter.complete();
                return;
            }

            int offset = 0;
            Instant deadline = Instant.now().plusSeconds(115);
            while (Instant.now().isBefore(deadline)) {
                List<StreamEvent> events = threadStore.awaitEvents(identity, messageId, offset, Duration.ofSeconds(10));
                if (events.isEmpty()) {
                    var snapshot = threadStore.find(identity, messageId).orElse(null);
                    if (offset == 0 && snapshot != null && snapshot.answer() != null && snapshot.events().isEmpty()) {
                        streamLegacyAnswer(messageId, snapshot.answer(), emitter);
                        emitter.complete();
                        return;
                    }
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    continue;
                }
                for (StreamEvent event : events) {
                    send(emitter, eventName(event), event);
                    offset++;
                    if ("DONE".equals(event.type()) || "ERROR".equals(event.type())) {
                        emitter.complete();
                        return;
                    }
                }
            }
            send(emitter, "error", StreamEvent.error(messageId, "STREAM_TIMEOUT", "Sparky took too long to finish the response."));
            emitter.complete();
        } catch (Exception ex) {
            try {
                send(emitter, "error", StreamEvent.error(messageId, "STREAM_ERROR", "Sparky could not finish the response. Please try again."));
            } catch (IOException ignored) {
                // Client already disconnected.
            }
            emitter.completeWithError(ex);
        }
    }

    private void streamLegacyAnswer(UUID messageId, ChatThreadStore.CompletedAnswer answer, SseEmitter emitter) throws IOException {
        if (answer.tool() != null && !answer.tool().startsWith("assistant_") && !"driver_support_context".equals(answer.tool())) {
            send(emitter, "tool_call", StreamEvent.toolCall(messageId, answer.tool()));
            send(emitter, "tool_result", StreamEvent.toolResult(messageId, answer.tool(), true, answer.latencyMs()));
        }
        send(emitter, "token", StreamEvent.token(messageId, answer.text()));
        send(emitter, "done", StreamEvent.done(messageId));
    }

    private static String eventName(StreamEvent event) {
        return switch (event.type()) {
            case "TOKEN" -> "token";
            case "TOOL_CALL" -> "tool_call";
            case "TOOL_RESULT" -> "tool_result";
            case "DONE" -> "done";
            default -> "error";
        };
    }

    private void send(SseEmitter emitter, String eventName, StreamEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(event));
    }

    @PreDestroy
    void stopStreamExecutor() {
        executor.close();
    }
}
