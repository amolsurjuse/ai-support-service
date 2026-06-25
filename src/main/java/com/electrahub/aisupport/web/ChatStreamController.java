package com.electrahub.aisupport.web;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.StreamEvent;
import com.electrahub.aisupport.service.ChatThreadStore;
import com.electrahub.aisupport.service.DiagnosticAnswerService;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.servlet.http.HttpServletRequest;

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
    private final DiagnosticAnswerService answerService;
    private final AiSupportProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ChatStreamController(ChatThreadStore threadStore, DiagnosticAnswerService answerService, AiSupportProperties properties) {
        this.threadStore = threadStore;
        this.answerService = answerService;
        this.properties = properties;
    }

    @GetMapping(path = "/threads/{threadId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID threadId,
                      @RequestParam(name = "since") UUID messageId,
                      HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        String authorization = request.getHeader("Authorization");
        executor.submit(() -> streamAnswer(threadId, messageId, authorization, emitter));
        return emitter;
    }

    private void streamAnswer(UUID threadId, UUID messageId, String authorization, SseEmitter emitter) {
        try {
            var pending = threadStore.find(messageId)
                    .filter(message -> message.threadId().equals(threadId))
                    .orElse(null);
            if (pending == null) {
                send(emitter, "error", StreamEvent.error(messageId, "MESSAGE_NOT_FOUND", "I could not find that chat message. Please send it again."));
                emitter.complete();
                return;
            }

            var answer = answerService.answer(pending.content(), pending.context(), authorization);
            send(emitter, "tool_call", StreamEvent.toolCall(messageId, answer.toolName()));
            send(emitter, "tool_result", StreamEvent.toolResult(messageId, answer.toolName(), true, 1));

            if (!answer.contextSummary().isBlank()) {
                streamText(messageId, "I checked " + answer.contextSummary() + ".\n\n", emitter);
            }
            streamText(messageId, answer.text(), emitter);
            send(emitter, "done", StreamEvent.done(messageId));
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

    private void streamText(UUID messageId, String text, SseEmitter emitter) throws IOException, InterruptedException {
        String[] tokens = text.split("(?<=\\s)");
        for (String token : tokens) {
            if (!token.isEmpty()) {
                send(emitter, "token", StreamEvent.token(messageId, token));
                if (properties.streamTokenDelayMs() > 0) {
                    Thread.sleep(properties.streamTokenDelayMs());
                }
            }
        }
    }

    private void send(SseEmitter emitter, String eventName, StreamEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(event));
    }
}
