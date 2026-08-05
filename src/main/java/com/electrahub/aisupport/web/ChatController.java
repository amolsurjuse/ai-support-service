package com.electrahub.aisupport.web;

import com.electrahub.aisupport.model.ChatDtos.SendMessageRequest;
import com.electrahub.aisupport.model.ChatDtos.SendMessageResponse;
import com.electrahub.aisupport.service.ChatThreadStore;
import com.electrahub.aisupport.service.DiagnosticAnswerService;
import com.electrahub.aisupport.model.ChatDtos.StreamEvent;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {
    private final ChatThreadStore threadStore;
    private final DiagnosticAnswerService answerService;
    private final ExecutorService answerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    ChatController(ChatThreadStore threadStore, DiagnosticAnswerService answerService) {
        this.threadStore = threadStore;
        this.answerService = answerService;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    SendMessageResponse sendMessage(@Valid @RequestBody SendMessageRequest request,
                                    HttpServletRequest servletRequest,
                                    @RequestHeader(name = "Prefer", required = false) String prefer) {
        var pending = threadStore.create(request.threadId(), request.content(), request.context());
        String authorization = servletRequest.getHeader("Authorization");
        if (prefer != null && prefer.toLowerCase().contains("respond-async")) {
            answerExecutor.submit(() -> completeAsync(pending, authorization));
            return new SendMessageResponse(pending.threadId(), pending.messageId(), null, null, null);
        }
        long startedNanos = System.nanoTime();
        var answer = answerService.answer(request.content(), request.context(), authorization);
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
        var completed = threadStore.complete(
                        pending.messageId(),
                        new ChatThreadStore.CompletedAnswer(
                                answerService.renderForClient(answer),
                                answer.toolName(),
                                answer.contextSummary(),
                                latencyMs))
                .orElseThrow(() -> new IllegalStateException("Chat message expired before its answer was stored"));
        return new SendMessageResponse(
                pending.threadId(),
                pending.messageId(),
                completed.answer().text(),
                completed.answer().tool(),
                completed.answer().contextSummary()
        );
    }

    private void completeAsync(ChatThreadStore.PendingMessage pending, String authorization) {
        long startedNanos = System.nanoTime();
        try {
            var answer = answerService.answerStreaming(
                    pending.content(), pending.context(), authorization,
                    delta -> threadStore.publish(pending.messageId(), StreamEvent.token(pending.messageId(), delta)));
            int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
            threadStore.complete(
                    pending.messageId(),
                    new ChatThreadStore.CompletedAnswer(
                            answerService.renderForClient(answer), answer.toolName(), answer.contextSummary(), latencyMs));
            if (isBackendTool(answer.toolName())) {
                threadStore.publish(pending.messageId(), StreamEvent.toolCall(pending.messageId(), answer.toolName()));
                threadStore.publish(pending.messageId(), StreamEvent.toolResult(
                        pending.messageId(), answer.toolName(), true, latencyMs));
            }
            threadStore.publish(pending.messageId(), StreamEvent.done(pending.messageId()));
        } catch (RuntimeException ex) {
            threadStore.publish(pending.messageId(), StreamEvent.error(
                    pending.messageId(), "ANSWER_ERROR", "Sparky could not finish the response. Please try again."));
        }
    }

    private static boolean isBackendTool(String tool) {
        return tool != null && !tool.isBlank()
                && !tool.startsWith("assistant_")
                && !"driver_support_context".equals(tool);
    }

    @PreDestroy
    void stopAnswerExecutor() {
        answerExecutor.close();
    }
}
