package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticIntentRouterTest {
    private final DiagnosticIntentRouter router = new DiagnosticIntentRouter();

    @Test
    void routesAvailabilityToOnlyChargerBackend() {
        assertThat(router.route("Is this charger available?", context("driver")))
                .containsExactly(DiagnosticIntentRouter.CHARGER);
    }

    @Test
    void routesLivenessToOnlyConnectionBackend() {
        assertThat(router.route("Is this charger online? What was its heartbeat?", context("driver")))
                .containsExactly(DiagnosticIntentRouter.OCPP_CONNECTION);
    }

    @Test
    void routesWalletQuestionToOnlyPaymentBackend() {
        assertThat(router.route("What is my wallet balance?", context("driver")))
                .containsExactly(DiagnosticIntentRouter.PAYMENT);
    }

    @Test
    void routesSessionStatusToOnlySessionBackend() {
        assertThat(router.route("What is my active session status?", context("driver")))
                .containsExactly(DiagnosticIntentRouter.SESSION);
    }

    @Test
    void routesStartFailureToOnlyRelevantBackends() {
        assertThat(router.route("Why did charging start fail?", context("driver")))
                .containsExactlyInAnyOrder(
                        DiagnosticIntentRouter.SESSION,
                        DiagnosticIntentRouter.CHARGER,
                        DiagnosticIntentRouter.OCPP_CONNECTION);
    }

    @Test
    void doesNotRunLiveDiagnosticsForGeneralHelp() {
        assertThat(router.route("What can you help me with?", context("driver"))).isEmpty();
    }

    @Test
    void routesRawOcppHistoryOnlyForAdministrativeAudience() {
        assertThat(router.route("Show recent OCPP actions", context("driver")))
                .doesNotContain(DiagnosticIntentRouter.OCPP_HISTORY);
        assertThat(router.route("Show recent OCPP actions", context("admin")))
                .containsExactly(DiagnosticIntentRouter.OCPP_HISTORY);
    }

    private static ContextPayload context(String audience) {
        return new ContextPayload(
                "charger", "charger", null, "charger-1", "connector-1", "location-1", null, audience);
    }
}
