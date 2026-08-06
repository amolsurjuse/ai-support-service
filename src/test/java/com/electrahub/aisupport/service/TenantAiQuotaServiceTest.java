package com.electrahub.aisupport.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAiQuotaServiceTest {
    @Test
    void reservesInputEstimateAndBoundedOutputAllowance() {
        assertThat(TenantAiQuotaService.estimatedTokens("")).isEqualTo(181);
        assertThat(TenantAiQuotaService.estimatedTokens("1234")).isEqualTo(181);
        assertThat(TenantAiQuotaService.estimatedTokens("12345")).isEqualTo(182);
    }
}
