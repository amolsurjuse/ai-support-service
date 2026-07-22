package com.electrahub.aisupport.web;

import com.electrahub.aisupport.model.ChatDtos.SendMessageRequest;
import com.electrahub.aisupport.model.ChatDtos.SendMessageResponse;
import com.electrahub.aisupport.service.ChatThreadStore;
import com.electrahub.aisupport.service.DiagnosticAnswerService;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {
    private final ChatThreadStore threadStore;
    private final DiagnosticAnswerService answerService;

    ChatController(ChatThreadStore threadStore, DiagnosticAnswerService answerService) {
        this.threadStore = threadStore;
        this.answerService = answerService;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    SendMessageResponse sendMessage(@Valid @RequestBody SendMessageRequest request, HttpServletRequest servletRequest) {
        var pending = threadStore.create(request.threadId(), request.content(), request.context());
        long startedNanos = System.nanoTime();
        var answer = answerService.answer(request.content(), request.context(), servletRequest.getHeader("Authorization"));
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
}
