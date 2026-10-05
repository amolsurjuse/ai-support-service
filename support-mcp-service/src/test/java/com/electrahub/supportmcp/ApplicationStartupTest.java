package com.electrahub.supportmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "support-mcp.identity-secret=test-only-identity-key-at-least-32-characters",
        "support-mcp.memory-directory=${java.io.tmpdir}/support-mcp-startup-test",
        "support-mcp.cluster.enabled=false", "management.server.port=0"})
class ApplicationStartupTest {
    @Test void serviceStartsWithRequiredIdentityAndDisabledCollector() {}
}
