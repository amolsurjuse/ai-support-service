package com.electrahub.supportmcp;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Component
public class SupportTools {
    private final ObjectMapper mapper;
    private final GatewayEvidenceClient evidence;
    private final ClusterContextCollector cluster;
    private final JsonNode flow;

    public SupportTools(ObjectMapper mapper, GatewayEvidenceClient evidence, ClusterContextCollector cluster) throws Exception {
        this.mapper = mapper; this.evidence = evidence; this.cluster = cluster;
        try (var input = getClass().getResourceAsStream("/knowledge/charging-session.json")) {
            if (input == null) throw new IllegalStateException("Reviewed flow catalog is missing");
            byte[] bytes = input.readAllBytes();
            var catalog = mapper.readTree(bytes);
            var root = mapper.createObjectNode();
            root.set("definition", catalog); root.put("source", "versioned-reviewed-repository-catalog");
            root.put("liveDeploymentVerified", false);
            try (var repositories = getClass().getResourceAsStream("/knowledge/repository-catalog.json")) {
                if (repositories == null) throw new IllegalStateException("Repository catalog is missing");
                root.set("repositoryCatalog", mapper.readTree(repositories));
            }
            root.put("revision", ContextMemory.digest(mapper.writeValueAsString(root)));
            this.flow = root;
        }
    }

    List<Map<String, Object>> definitions() {
        return List.of(
                tool("get_service_topology", "Read fresh sanitized cluster inventory. Readiness is not evidence of a session cause.", Map.of(), List.of()),
                tool("get_flow_definition", "Read repository-backed expected charging flow with provenance; never treat expected behavior as observed events.",
                        Map.of("flowId", Map.of("type", "string", "enum", List.of("charging-session"))), List.of("flowId")),
                tool("get_org_context", "Read the authorized selected session's stored organizational mapping. No cross-organization enumeration.", sessionProperty(), List.of("sessionId")),
                tool("get_session_evidence", "Read authorized selected-session evidence: commands, payment, meter values, subscriptions, billing and OCPP gaps.", sessionProperty(), List.of("sessionId")));
    }

    Map<String, Object> call(String name, JsonNode arguments, TrustedSupportIdentity.Identity identity) {
        Object result = switch (name) {
            case "get_service_topology" -> { requireKeys(arguments, Set.of()); yield cluster.topology(); }
            case "get_flow_definition" -> {
                requireKeys(arguments, Set.of("flowId"));
                if (!"charging-session".equals(arguments.path("flowId").asText())) throw new InvalidArguments();
                yield flow;
            }
            case "get_org_context", "get_session_evidence" -> {
                requireKeys(arguments, Set.of("sessionId"));
                UUID id;
                try {
                    String raw = arguments.path("sessionId").asText();
                    id = UUID.fromString(raw);
                    if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
                } catch (Exception ex) { throw new InvalidArguments(); }
                JsonNode report = evidence.session(id, identity);
                if (!report.path("facts").isArray() || !report.path("gaps").isArray() || !report.path("collectedAt").isTextual())
                    throw new GatewayEvidenceClient.EvidenceUnavailable();
                yield name.equals("get_org_context") ? organization(report, identity) : session(report, identity);
            }
            default -> throw new InvalidArguments();
        };
        String encoded = mapper.writeValueAsString(result);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 96_000)
            throw new GatewayEvidenceClient.EvidenceUnavailable();
        return Map.of("content", List.of(Map.of("type", "text", "text", encoded)), "structuredContent", result, "isError", false);
    }

    private Object session(JsonNode report, TrustedSupportIdentity.Identity identity) {
        var safe = mapper.createObjectNode();
        for (String key : List.of("sessionId", "collectedAt", "facts", "gaps")) safe.set(key, report.path(key));
        validateEvidenceStrings(report.path("facts")); validateEvidenceStrings(report.path("gaps"));
        // Reuse only this authorized report. No cross-request customer evidence cache.
        safe.set("organizationContext", mapper.valueToTree(organization(report, identity)));
        return safe;
    }

    private Object organization(JsonNode report, TrustedSupportIdentity.Identity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", report.path("sessionId").asText());
        result.put("collectedAt", report.path("collectedAt").asText());
        result.put("requesterTenantId", identity.tenantId());
        result.put("source", "authorized-session-stored-organization-context");
        result.put("persistedLocally", false);
        List<String> gaps = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String key : List.of("tenantId", "enterpriseId", "networkId", "locationId", "chargerId")) {
            JsonNode value = report.path("organizationContext").path(key);
            if (value.isTextual() && value.asText().matches("[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}")) mapping.put(key, value.asText());
            else gaps.add(key + " is not established by the authorized session response.");
        }
        result.put("organizationContext", mapping); result.put("gaps", gaps);
        return result;
    }

    private static void validateEvidenceStrings(JsonNode values) {
        if (values.size() > 300) throw new GatewayEvidenceClient.EvidenceUnavailable();
        for (JsonNode value : values) if (!value.isTextual() || value.asText().length() > 16_384)
            throw new GatewayEvidenceClient.EvidenceUnavailable();
    }
    private static void requireKeys(JsonNode arguments, Set<String> keys) {
        if (arguments == null || !arguments.isObject() || arguments.size() != keys.size()) throw new InvalidArguments();
        for (String key : keys) if (!arguments.path(key).isTextual()) throw new InvalidArguments();
    }
    private static Map<String, Object> sessionProperty() {
        return Map.of("sessionId", Map.of("type", "string", "format", "uuid"));
    }
    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false),
                "annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }
    static Map<String, Object> unavailable() {
        return unavailable(GatewayEvidenceClient.FailureReason.UNAVAILABLE);
    }
    static Map<String, Object> unavailable(GatewayEvidenceClient.FailureReason reason) {
        String message = "Authorized evidence is unavailable, incomplete, too large or access was denied. No cause is confirmed. Retry or escalate through scoped support.";
        return Map.of("content", List.of(Map.of("type", "text", "text", message)), "isError", true,
                "structuredContent", Map.of("status", "UNAVAILABLE", "reason", reason.name()));
    }
    static class InvalidArguments extends RuntimeException {}
}
