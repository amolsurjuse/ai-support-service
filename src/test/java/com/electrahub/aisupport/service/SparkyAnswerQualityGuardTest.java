package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SparkyAnswerQualityGuardTest {

    private final SparkyAnswerQualityGuard guard = new SparkyAnswerQualityGuard();

    @Test
    void acceptsGroundedDriverAnswer() {
        var evaluation = guard.evaluate(
                "This connector is currently charging, so it is not available. Choose another Available connector before you start a session.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "check_charger_availability",
                        "The selected charger is busy and this connector is not available right now. Choose another available connector.",
                        "charger: EH-1"),
                "Is this charger available?",
                new ContextPayload("map", "charger", "EH-1", "EH-1", "CON-1", "LOC-1", null, "driver"));

        assertThat(evaluation.accepted()).isTrue();
        assertThat(evaluation.answer()).contains("not available");
    }

    @Test
    void stripsHiddenThinkingBeforeAcceptingAnswer() {
        var evaluation = guard.evaluate(
                "<think>Private reasoning</think>\nThe charging session is idle. Unplug the vehicle to finish the session and stop idle fees.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "diagnose_idle_remote_stop",
                        "The session is idle and remains active until the vehicle is unplugged.",
                        "session: S-1"),
                "Why is the session still idle?",
                new ContextPayload("liveCharging", "session", "S-1", "EH-1", "CON-1", "LOC-1", "S-1", "driver"));

        assertThat(evaluation.accepted()).isTrue();
        assertThat(evaluation.answer()).doesNotContain("<think>");
    }

    @Test
    void rejectsPromptLeak() {
        var evaluation = guard.evaluate(
                "According to the authoritative draft and Backend facts, the charger is available.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "check_charger_availability",
                        "The connector is available.",
                        "charger: EH-1"),
                "Is this charger available?",
                new ContextPayload("map", "charger", "EH-1", "EH-1", "CON-1", "LOC-1", null, "driver"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("prompt_or_reasoning_leak");
    }

    @Test
    void rejectsInternalServiceNamesForDriverAudience() {
        var evaluation = guard.evaluate(
                "session-service reports this charger is unavailable. Please choose another charger.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "check_charger_availability",
                        "The charger is unavailable.",
                        "charger: EH-1"),
                "Is this charger available?",
                new ContextPayload("map", "charger", "EH-1", "EH-1", "CON-1", "LOC-1", null, "driver"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("driver_internal_detail");
    }

    @Test
    void rejectsPaymentAnswerThatChangesAuthorizationReversalOutcome() {
        var evaluation = guard.evaluate(
                "The hold will not be applied when the charger rejects remote start. Check the logs.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "explain_payment_authorization",
                        "Authorize the configured hold before remote start. If the charger does not confirm start, void or reverse the unused authorization promptly.",
                        "session: S-1"),
                "What happens to a credit-card hold when remote start fails?",
                new ContextPayload("payments", "session", "S-1", "EH-1", "CON-1", "LOC-1", "S-1", "admin"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("authoritative_intent_not_preserved");
    }

    @Test
    void rejectsAlternativeAnswerThatDropsChargerAndConnectorReferences() {
        var evaluation = guard.evaluate(
                "An available CCS charger is 0.4 mi away. Open it before starting.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "find_charger_alternatives",
                        "Available CCS connector: EH-DEMO-002 / CON-DEMO-002, 0.4 mi away.",
                        "charger: EH-DEMO-001"),
                "Find another CCS charger",
                new ContextPayload("map", "charger", "EH-DEMO-001", "EH-DEMO-001", "CON-DEMO-001", "LOC-1", null, "driver"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("authoritative_intent_not_preserved");
    }

    @Test
    void rejectsPastSessionAnswerThatBlamesMissingContextForFailure() {
        var evaluation = guard.evaluate(
                "Your last charge failed because no selected session was supplied. Open history.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "diagnose_past_session",
                        "I cannot diagnose why a past session failed without the selected session. Missing context is not the reason for the failure.",
                        ""),
                "Why did my last charge fail?",
                new ContextPayload("dashboard", null, null, null, null, null, null, "driver"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("authoritative_intent_not_preserved");
    }

    @Test
    void rejectsSpeculativePastSessionAnswerBeforeTheSelectedSessionIsOpened() {
        var evaluation = guard.evaluate(
                "Your last charge could have failed for several reasons, including a connection or payment issue. Open history for details.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "diagnose_past_session",
                        "I cannot diagnose why a past charging session failed without the selected session. Missing context is not the reason for the failure.",
                        ""),
                "Why did my last charge fail?",
                new ContextPayload("dashboard", null, null, null, null, null, null, "driver"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("authoritative_intent_not_preserved");
    }

    @Test
    void rejectsPastSessionAnswerThatClaimsTheChargeDidNotComplete() {
        var evaluation = guard.evaluate(
                "Your last charge didn't complete, but I cannot see the session details. Open charging history.",
                new DiagnosticAnswerService.DiagnosticAnswer(
                        "diagnose_past_session",
                        "A precise diagnosis needs the selected session. Open the charging history entry so the session can be checked.",
                        ""),
                "Why did my last charge fail?",
                new ContextPayload("dashboard", null, null, null, null, null, null, "driver"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.reason()).isEqualTo("authoritative_intent_not_preserved");
    }
}
