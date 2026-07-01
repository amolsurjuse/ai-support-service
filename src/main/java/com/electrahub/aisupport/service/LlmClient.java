package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

interface LlmClient {
    boolean available();

    LlmCompletion complete(LlmPrompt prompt);

    record LlmPrompt(
            String userMessage,
            ContextPayload context,
            DiagnosticAnswerService.DiagnosticAnswer deterministicAnswer,
            BackendDiagnosticsClient.DiagnosticsSnapshot diagnostics
    ) {
    }

    record LlmCompletion(boolean ok, String answer, String provider, String model, String error) {
        static LlmCompletion disabled() {
            return new LlmCompletion(false, "", "none", "none", "LLM provider is disabled");
        }

        static LlmCompletion success(String answer, String provider, String model) {
            return new LlmCompletion(true, answer == null ? "" : answer.trim(), provider, model, null);
        }

        static LlmCompletion failure(String provider, String model, String error) {
            return new LlmCompletion(false, "", provider, model, error);
        }
    }
}
