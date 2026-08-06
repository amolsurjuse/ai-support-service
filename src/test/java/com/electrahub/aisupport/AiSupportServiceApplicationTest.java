package com.electrahub.aisupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "electrahub.ai-support.provider-enabled=false",
                "electrahub.ai-support.local-runtime.warmup-enabled=false",
                "electrahub.ai-support.multitenancy.quota-enabled=false"
        })
class AiSupportServiceApplicationTest {
    @Test
    void applicationContextStarts() {
    }
}
