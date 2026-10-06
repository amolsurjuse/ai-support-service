package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportInvestigationAnswerRendererTest {
    private static final String ID = "11111111-2222-4333-8444-555555555555";
    private final BackendDiagnosticsClient.DiagnosticsSnapshot evidence = new BackendDiagnosticsClient.DiagnosticsSnapshot(
            List.of("Session " + ID + ": status=PREPARING; startedAt=unavailable; stoppedAt=unavailable",
                    "Payment authorization snapshot: amount=20.00 EUR; status=AUTHORIZED"),
            List.of("Physical start evidence unavailable"), List.of("Flow version 1 describes expected behavior"));

    @Test void modelCanOnlySelectAValidatedReadOnlyNextCheckAndCannotDumpFacts() {
        String output = SupportInvestigationAnswerRenderer.render("{\"findingIndexes\":[1,0],\"nextCheck\":\"START_CHAIN\"}", evidence).orElseThrow();
        assertThat(output).startsWith("Suggested next check: Compare start request")
                .doesNotContain(evidence.facts().get(0), evidence.facts().get(1), "Flow version");
    }

    @Test void rejectsInventedAmountsActionsIndexesAndUnstructuredProse() {
        for (String output : List.of("The session failed because payment declined and I refunded 40 EUR.",
                "{\"findingIndexes\":[0],\"nextCheck\":\"REFUND\"}",
                "{\"findingIndexes\":[99],\"nextCheck\":\"BILLING\"}",
                "{\"findingIndexes\":[0,0],\"nextCheck\":\"BILLING\"}",
                "{\"findingIndexes\":[1.0],\"nextCheck\":\"BILLING\"}",
                "{\"findingIndexes\":[0],\"nextCheck\":\"BILLING\",\"amount\":40}",
                "{\"findingIndexes\":[],\"nextCheck\":\"BILLING\"}")) {
            assertThat(SupportInvestigationAnswerRenderer.render(output, evidence)).isEmpty();
        }
    }

    @Test void bothAnswerTransportsRejectInventedDiagnosisAndKeepInfrastructureOutOfTheSummary() {
        var backend = mock(BackendDiagnosticsClient.class);
        when(backend.collect(anyString(), any(), anyString(), any())).thenReturn(evidence);
        var llm = mock(LlmClient.class);
        when(llm.available()).thenReturn(true);
        when(llm.complete(any())).thenReturn(LlmClient.LlmCompletion.success(
                "The session failed because payment declined and I refunded 40 EUR.", "test", "test"));
        var service = new DiagnosticAnswerService(mock(AiSupportProperties.class), new PiiRedactor(), backend, llm);
        var context = new ContextPayload("charging-sessions", "session", ID, null, null, null, ID, "support", Map.of("responseMode", "SELECTED_RECORD"));
        var identity = new IdentityContext("tenant", "agent", Set.of("SUPPORT"), true);
        try {
            var response = service.answer("Diagnose", context, "Bearer token", identity).text();
            assertThat(response).contains("20 EUR", "not proof of the current bank hold or capture", "complete start sequence is not verified")
                    .doesNotContain("40 EUR", "I refunded", "Flow version 1", "Operational context", "status=PREPARING");
            StringBuilder stream = new StringBuilder();
            service.answerStreaming("Diagnose", context, "Bearer token", identity, stream::append);
            assertThat(stream.toString()).isEqualTo(response);
            when(llm.complete(any())).thenReturn(LlmClient.LlmCompletion.success(
                    "{\"findingIndexes\":[1],\"nextCheck\":\"AUTHORIZATION\"}", "test", "test"));
            assertThat(service.answer("Diagnose", context, "Bearer token", identity).text())
                    .contains("Suggested next check:", "20 EUR").doesNotContain("Flow version 1", "Key recorded findings:");
        } finally { service.closeSupportSynthesis(); }
    }
}
