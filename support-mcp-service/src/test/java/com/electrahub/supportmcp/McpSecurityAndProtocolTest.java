package com.electrahub.supportmcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class McpSecurityAndProtocolTest {
    static final String SECRET = "test-only-identity-key-at-least-32-characters";
    static final UUID SESSION = UUID.fromString("12db94ed-258e-4569-842e-003395b74582");
    final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;
    MockMvc mvc;
    AtomicInteger reads;

    @BeforeEach void setup() throws Exception {
        var memory = new ContextMemory(mapper, directory.toString(), "test", "electrahub", 180);
        var metrics = new SimpleMeterRegistry();
        var cluster = new ClusterContextCollector(mapper, memory, metrics, false, "https://kubernetes.default.svc", "electrahub", "absent-token", "absent-ca");
        reads = new AtomicInteger();
        var gateway = new GatewayEvidenceClient(mapper, "http://localhost:1") {
            @Override JsonNode session(UUID id, TrustedSupportIdentity.Identity identity) {
                reads.incrementAndGet();
                return mapper.valueToTree(Map.of("sessionId", id.toString(), "collectedAt", Instant.now().toString(),
                        "facts", List.of("Session is ACTIVE; last meter unavailable"), "gaps", List.of("Meter timing unavailable"),
                        "organizationContext", Map.of("enterpriseId", "enterprise-a", "networkId", "network-a", "locationId", "location-a", "chargerId", "charger-a"),
                        "unexpectedRawSecret", "must-not-escape"));
            }
        };
        var tools = new SupportTools(mapper, gateway, cluster);
        mvc = MockMvcBuilders.standaloneSetup(new McpController(mapper, tools, metrics))
                .addFilters(new TrustedSupportIdentity(mapper, SECRET)).build();
    }

    @Test void onlyExplicitSystemAdminAndSupportRolesMayInitializeOrReadTools() throws Exception {
        for (String role : List.of("SYSTEM_ADMIN", "SUPPORT"))
            mvc.perform(signed(post("/mcp"), role).content(rpc("tools/list", Map.of())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(4));
        for (String role : List.of("TENANT_ADMIN", "ENTERPRISE_ADMIN", "NETWORK_ADMIN", "LOCATION_ADMIN", "ADMIN_READ_ONLY", "DRIVER", "USER", "ROLE_SUPPORT"))
            mvc.perform(signed(post("/mcp"), role).content(rpc("tools/list", Map.of())))
                    .andExpect(status().isForbidden());
        assertThat(reads).hasValue(0);
    }

    @Test void missingExpiredTamperedAndCrossTenantIdentityFailClosed() throws Exception {
        mvc.perform(post("/mcp").contentType("application/json").content(rpc("tools/list", Map.of()))).andExpect(status().isUnauthorized());
        mvc.perform(signed(post("/mcp"), "SUPPORT", Instant.now().minusSeconds(1).toEpochMilli(), "tenant-a", "tenant-a")
                .content(rpc("tools/list", Map.of()))).andExpect(status().isUnauthorized());
        mvc.perform(signed(post("/mcp"), "SUPPORT", Instant.now().plusSeconds(500).toEpochMilli(), "tenant-a", "tenant-a")
                .content(rpc("tools/list", Map.of()))).andExpect(status().isUnauthorized());
        mvc.perform(signed(post("/mcp"), "SUPPORT", Instant.now().plusSeconds(30).toEpochMilli(), "tenant-a", "tenant-b")
                .content(rpc("tools/list", Map.of()))).andExpect(status().isUnauthorized());
        var tampered = signed(post("/mcp"), "SUPPORT");
        tampered.with(request -> { request.removeHeader(TrustedSupportIdentity.SIGNATURE); request.addHeader(TrustedSupportIdentity.SIGNATURE, "invalid"); return request; });
        mvc.perform(tampered.content(rpc("tools/list", Map.of()))).andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> new TrustedSupportIdentity(mapper, "electrahub-local-access-context-secret")).isInstanceOf(IllegalStateException.class);
    }

    @Test void browserOriginIsRejectedAndAllMethodsRequireIdentity() throws Exception {
        mvc.perform(signed(post("/mcp"), "SUPPORT").header("Origin", "https://malicious.example").content(rpc("ping", Map.of())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/mcp")).andExpect(status().isUnauthorized());
        mvc.perform(signed(get("/mcp"), "SUPPORT")).andExpect(status().isMethodNotAllowed());
    }

    @Test void initializesWithPinnedVersionAndAcknowledgesNotification() throws Exception {
        mvc.perform(signed(post("/mcp"), "SUPPORT").content(rpc("initialize", Map.of("protocolVersion", McpController.VERSION,
                        "capabilities", Map.of(), "clientInfo", Map.of("name", "sparky", "version", "1")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.protocolVersion").value(McpController.VERSION))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false));
        mvc.perform(signed(post("/mcp"), "SUPPORT").content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
    }

    @Test void rejectsUnknownToolsExtraParametersInvalidUuidAndBadProtocol() throws Exception {
        for (Map<String, Object> params : List.of(
                Map.<String, Object>of("name", "execute_sql", "arguments", Map.of()),
                Map.<String, Object>of("name", "get_service_topology", "arguments", Map.of("namespace", "other")),
                Map.<String, Object>of("name", "get_session_evidence", "arguments", Map.of("sessionId", "../../secret")),
                Map.<String, Object>of("name", "get_session_evidence", "arguments", Map.of("sessionId", SESSION.toString(), "tenantId", "other"))))
            mvc.perform(signed(post("/mcp"), "SUPPORT").content(rpc("tools/call", params)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32602));
        var oldProtocol = signed(post("/mcp"), "SUPPORT").with(request -> {
            request.removeHeader("MCP-Protocol-Version"); request.addHeader("MCP-Protocol-Version", "2024-11-05"); return request;
        });
        mvc.perform(oldProtocol.content(rpc("tools/list", Map.of()))).andExpect(status().isBadRequest());
        mvc.perform(signed(post("/mcp"), "SUPPORT").content("{")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(-32700));
        mvc.perform(signed(post("/mcp"), "SUPPORT").content(" ".repeat(16_385))).andExpect(status().isPayloadTooLarge());
        assertThat(reads).hasValue(0);
    }

    @Test void sessionAndOrganizationAreFreshScopedProjectionsNeverCachedOrRaw() throws Exception {
        String session = mvc.perform(signed(post("/mcp"), "SUPPORT").content(rpc("tools/call",
                        Map.of("name", "get_session_evidence", "arguments", Map.of("sessionId", SESSION.toString())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.result.structuredContent.organizationContext.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.result.structuredContent.organizationContext.organizationContext.enterpriseId").value("enterprise-a"))
                .andExpect(jsonPath("$.result.structuredContent.organizationContext.organizationContext.tenantId").doesNotExist())
                .andExpect(jsonPath("$.result.isError").value(false)).andReturn().getResponse().getContentAsString();
        assertThat(session).doesNotContain("unexpectedRawSecret", "must-not-escape");
        mvc.perform(signed(post("/mcp"), "SUPPORT").content(rpc("tools/call",
                        Map.of("name", "get_org_context", "arguments", Map.of("sessionId", SESSION.toString())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.result.structuredContent.organizationContext.enterpriseId").value("enterprise-a"))
                .andExpect(jsonPath("$.result.structuredContent.requesterTenantId").value("tenant-a"))
                .andExpect(jsonPath("$.result.structuredContent.organizationContext.tenantId").doesNotExist());
        assertThat(reads).hasValue(2);
        assertThat(directory.toFile().list()).isEmpty();
    }

    @Test void flowIsVersionedAndDisabledClusterDoesNotInventTopology() throws Exception {
        mvc.perform(signed(post("/mcp"), "SUPPORT").content(rpc("tools/call", Map.of("name", "get_flow_definition", "arguments", Map.of("flowId", "charging-session")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.liveDeploymentVerified").value(false))
                .andExpect(jsonPath("$.result.structuredContent.definition.sources").isArray());
        mvc.perform(signed(post("/mcp"), "SUPPORT").content(rpc("tools/call", Map.of("name", "get_service_topology", "arguments", Map.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.enabled").value(false))
                .andExpect(jsonPath("$.result.structuredContent.resources").isEmpty());
    }

    String rpc(String method, Map<String, ?> params) { return mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "1", "method", method, "params", params)); }
    MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder, String role) throws Exception {
        return signed(builder, role, Instant.now().plusSeconds(30).toEpochMilli(), "tenant-a", "tenant-a");
    }
    MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder, String role, long expires, String tenant, String headerTenant) throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(Map.of(
                "version", 1, "tenantId", tenant, "userId", "support-a", "roles", List.of(role), "expiresAt", expires)));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        return builder.contentType("application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpController.VERSION).header("Authorization", "Bearer user-token")
                .header("X-ElectraHub-Tenant-Id", headerTenant).header("X-ElectraHub-User-Id", "support-a")
                .header(TrustedSupportIdentity.CONTEXT, payload).header(TrustedSupportIdentity.SIGNATURE, signature);
    }
}
