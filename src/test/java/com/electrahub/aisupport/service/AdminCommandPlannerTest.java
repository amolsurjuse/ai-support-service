package com.electrahub.aisupport.service;

import org.junit.jupiter.api.Test;

import static com.electrahub.aisupport.service.AdminToolRegistry.ToolId.ANALYTICS_OVERVIEW;
import static com.electrahub.aisupport.service.AdminToolRegistry.ToolId.CHARGERS_LIST;
import static com.electrahub.aisupport.service.AdminToolRegistry.ToolId.CONNECTOR_STATUS_SUMMARY;
import static com.electrahub.aisupport.service.AdminToolRegistry.ToolId.SESSIONS_SEARCH;
import static org.assertj.core.api.Assertions.assertThat;

class AdminCommandPlannerTest {
    private final AdminCommandPlanner planner = new AdminCommandPlanner();

    @Test
    void routesAnalyticsWithoutUsingAnLlm() {
        assertThat(planner.plan("show the revenue dashboard"))
                .hasValueSatisfying(plan -> assertThat(plan.toolId()).isEqualTo(ANALYTICS_OVERVIEW));
    }

    @Test
    void routesFailedSessionsWithBoundedStateFilter() {
        assertThat(planner.plan("show failed charging sessions"))
                .hasValueSatisfying(plan -> {
                    assertThat(plan.toolId()).isEqualTo(SESSIONS_SEARCH);
                    assertThat(plan.queryName()).isEqualTo("state");
                    assertThat(plan.queryValue()).isEqualTo("FAILED");
                });
    }

    @Test
    void routesOfflineChargersToReadOnlyInventory() {
        assertThat(planner.plan("which chargers are offline?"))
                .hasValueSatisfying(plan -> assertThat(plan.toolId()).isEqualTo(CONNECTOR_STATUS_SUMMARY));
    }

    @Test
    void routesGenericChargerInventorySeparately() {
        assertThat(planner.plan("list chargers"))
                .hasValueSatisfying(plan -> assertThat(plan.toolId()).isEqualTo(CHARGERS_LIST));
    }

    @Test
    void blocksMutationCommandsBeforeToolSelection() {
        assertThat(planner.plan("disable charger EH-100"))
                .hasValueSatisfying(plan -> {
                    assertThat(plan.mutation()).isTrue();
                    assertThat(plan.toolId()).isNull();
                });
    }

    @Test
    void doesNotInventAToolForUnknownText() {
        assertThat(planner.plan("explain our sustainability strategy")).isEmpty();
    }
}
