package com.electrahub.aisupport.web;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.model.ChatDtos.SendMessageRequest;
import com.electrahub.aisupport.security.AiToolAuthorizationService;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import com.electrahub.aisupport.service.AiAuditService;
import com.electrahub.aisupport.service.ChatThreadStore;
import com.electrahub.aisupport.service.DiagnosticAnswerService;
import com.electrahub.aisupport.service.TenantAiQuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatAnalysisAccessTest {
    @Test
    void syncAndAsyncAnalysisDenyBeforeQuotaThreadCreationOrEvidenceWithSpoofedAudience() {
        var store = mock(ChatThreadStore.class);
        var answers = mock(DiagnosticAnswerService.class);
        var resolver = mock(TrustedIdentityContextResolver.class);
        var quota = mock(TenantAiQuotaService.class);
        var audit = mock(AiAuditService.class);
        var controller = new ChatController(store, answers, resolver, new AiToolAuthorizationService(), audit, quota);
        try {
            for (String role : Set.of("ADMIN_READ_ONLY", "TENANT_ADMIN", "ENTERPRISE", "NETWORK", "LOCATION", "USER")) {
                var servlet = new MockHttpServletRequest();
                when(resolver.resolve(servlet)).thenReturn(new IdentityContext("tenant", "actor", Set.of(role), true));
                var context = new ContextPayload("charging-sessions", "session", "id", null, null, null,
                        "id", "driver", Map.of("responseMode", "SELECTED_RECORD"));
                var request = new SendMessageRequest(null, "Diagnose this session", context);
                assertThatThrownBy(() -> controller.sendMessage(request, servlet, null)).hasMessageContaining("403");
                assertThatThrownBy(() -> controller.sendMessage(request, servlet, "respond-async")).hasMessageContaining("403");
            }
            verifyNoInteractions(store, answers, quota, audit);
        } finally {
            controller.stopAnswerExecutor();
        }
    }
}
