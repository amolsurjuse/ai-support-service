package com.electrahub.aisupport.service;

import com.electrahub.aisupport.model.ChatDtos.ContextPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElectraHubKnowledgeBaseTest {

    @Test
    void returnsSimulatorSecurityCodeGuidance() {
        String facts = ElectraHubKnowledgeBase.relevantFacts(
                "The simulator asks for security code when I open from mobile app to unplug",
                new ContextPayload("liveCharging", "session", null, "EH-1", "CON-1", null, null, "driver"));

        assertThat(facts).contains("security code");
        assertThat(facts).contains("without typing it");
        assertThat(facts).contains("idle fee is disabled");
    }

    @Test
    void returnsCardPresentAdminGuidance() {
        String facts = ElectraHubKnowledgeBase.relevantFacts(
                "For a tap credit card session, what should admin see for payment and transaction id?",
                new ContextPayload("adminSessions", "session", null, null, null, null, null, "admin"));

        assertThat(facts).contains("Credit Card");
        assertThat(facts).contains("masked card number");
        assertThat(facts).contains("ElectraHub payment authorization id");
    }
}
