package com.electrahub.aisupport.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMutationPlannerTest {
    private final AdminMutationPlanner planner = new AdminMutationPlanner();
    private final UUID sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void parsesOnlyExplicitStopSessionWithUuid() {
        assertThat(planner.parse("stop charging session " + sessionId))
                .hasValueSatisfying(command -> {
                    assertThat(command.operation()).isEqualTo(AdminMutationPlanner.Operation.STOP_SESSION);
                    assertThat(command.targetId()).isEqualTo(sessionId);
                    assertThat(command.confirmation()).isFalse();
                });
        assertThat(planner.parse("stop the broken session")).isEmpty();
    }

    @Test
    void confirmationMustBeTheEntireCommand() {
        assertThat(planner.parse("confirm " + sessionId))
                .hasValueSatisfying(command -> {
                    assertThat(command.confirmation()).isTrue();
                    assertThat(command.confirmationId()).isEqualTo(sessionId);
                });
        assertThat(planner.parse("please confirm " + sessionId)).isEmpty();
    }
}
