package com.electrahub.aisupport.web;

import com.electrahub.aisupport.model.ChatDtos.SendMessageRequest;
import com.electrahub.aisupport.model.ChatDtos.SendMessageResponse;
import com.electrahub.aisupport.service.ChatThreadStore;
import com.electrahub.aisupport.service.DiagnosticAnswerService;
import com.electrahub.aisupport.model.ChatDtos.StreamEvent;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.service.AiAuditService;
import com.electrahub.aisupport.service.TenantAiQuotaService;

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
    private final TrustedIdentityContextResolver identityResolver;
    private final AiToolAuthorizationService toolAuthorization;
    private final AiAuditService auditService;
    private final TenantAiQuotaService quotaService;
    private final ExecutorService answerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    ChatController(ChatThreadStore threadStore,
                   DiagnosticAnswerService answerService,
                   TrustedIdentityContextResolver identityResolver,
                   AiToolAuthorizationService toolAuthorization,
                   AiAuditService auditService,
                   TenantAiQuotaService quotaService) {
        this.threadStore = threadStore;
        this.answerService = answerService;
        this.identityResolver = identityResolver;
        this.toolAuthorization = toolAuthorization;
        this.auditService = auditService;
        this.quotaService = quotaService;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    SendMessageResponse sendMessage(@Valid @RequestBody SendMessageRequest request,
                                    HttpServletRequest servletRequest,
                                    @RequestHeader(name = "Prefer", required = false) String prefer) {
        IdentityContext identity = identityResolver.resolve(servletRequest);
        toolAuthorization.requireAudienceAccess(identity, request.context());
        quotaService.admit(identity);
        var pending = threadStore.create(identity, request.threadId(), request.content(), request.context());
        String authorization = servletRequest.getHeader("Authorization");
        if (prefer != null && prefer.toLowerCase().contains("respond-async")) {
            answerExecutor.submit(() -> completeAsync(identity, pending, authorization));
            return new SendMessageResponse(pending.threadId(), pending.messageId(), null, null, null);
        }
        long startedNanos = System.nanoTime();
        DiagnosticAnswerService.DiagnosticAnswer answer;
        try {
            answer = answerService.answer(request.content(), request.context(), authorization, identity);
        } catch (RuntimeException ex) {
            int failedLatencyMs = (int) Math.min(
                    Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
            auditService.chatCompleted(
                    identity, pending.threadId(), pending.messageId(), "answer_error", failedLatencyMs, false);
            throw ex;
        }
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
        var completed = threadStore.complete(
                        identity,
                        pending.messageId(),
                        new ChatThreadStore.CompletedAnswer(
                                answerService.renderForClient(answer),
                                answer.toolName(),
                                answer.contextSummary(),
                                latencyMs))
                .orElseThrow(() -> new IllegalStateException("Chat message expired before its answer was stored"));
        auditService.chatCompleted(
                identity, pending.threadId(), pending.messageId(), answer.toolName(), latencyMs, true);
        return new SendMessageResponse(
                pending.threadId(),
                pending.messageId(),
                completed.answer().text(),
                completed.answer().tool(),
                completed.answer().contextSummary()
        );
    }

    private void completeAsync(IdentityContext identity, ChatThreadStore.PendingMessage pending, String authorization) {
        long startedNanos = System.nanoTime();
        try {
            var answer = answerService.answerStreaming(
                    pending.content(), pending.context(), authorization, identity,
                    delta -> threadStore.publish(identity, pending.messageId(), StreamEvent.token(pending.messageId(), delta)));
            int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
            threadStore.complete(
                    identity,
                    pending.messageId(),
                    new ChatThreadStore.CompletedAnswer(
                            answerService.renderForClient(answer), answer.toolName(), answer.contextSummary(), latencyMs));
            if (isBackendTool(answer.toolName())) {
                threadStore.publish(identity, pending.messageId(), StreamEvent.toolCall(pending.messageId(), answer.toolName()));
                threadStore.publish(identity, pending.messageId(), StreamEvent.toolResult(
                        pending.messageId(), answer.toolName(), true, latencyMs));
            }
            threadStore.publish(identity, pending.messageId(), StreamEvent.done(pending.messageId()));
            auditService.chatCompleted(
                    identity, pending.threadId(), pending.messageId(), answer.toolName(), latencyMs, true);
        } catch (RuntimeException ex) {
            threadStore.publish(identity, pending.messageId(), StreamEvent.error(
                    pending.messageId(), "ANSWER_ERROR", "Sparky could not finish the response. Please try again."));
            int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
            auditService.chatCompleted(
                    identity, pending.threadId(), pending.messageId(), "answer_error", latencyMs, false);
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
