package com.electrahub.supportmcp;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Only sanitized infrastructure records are accepted here. Customer evidence never enters this store. */
@Component
public class ContextMemory {
    private final ObjectMapper mapper;
    private final Path file;
    private final String scope;
    private final long ttlSeconds;
    private volatile Snapshot snapshot;

    public ContextMemory(ObjectMapper mapper, @Value("${support-mcp.memory-directory}") String directory,
                         @Value("${support-mcp.cluster.id}") String cluster,
                         @Value("${support-mcp.cluster.namespaces}") String namespaces,
                         @Value("${support-mcp.cluster.ttl-seconds}") long ttlSeconds) {
        if (ttlSeconds < 30 || ttlSeconds > 900) throw new IllegalArgumentException("Topology TTL must be 30..900 seconds");
        this.mapper = mapper; this.ttlSeconds = ttlSeconds;
        this.scope = digest(cluster + "\n" + namespaces + "\n" + ttlSeconds);
        this.file = Path.of(directory).toAbsolutePath().normalize().resolve("topology-v1.json");
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file) && Files.size(file) <= 262_144) {
                var saved = mapper.readValue(Files.readAllBytes(file), Snapshot.class);
                if (valid(saved)) snapshot = saved;
            }
        } catch (Exception ex) {
            LoggerFactory.getLogger(getClass()).warn("Topology memory could not be restored; live reconciliation required");
        }
    }

    synchronized void publish(List<Resource> resources) throws Exception {
        if (resources.size() > 256 || resources.stream().anyMatch(r -> !validResource(r)))
            throw new IllegalArgumentException("Invalid sanitized inventory");
        var ordered = resources.stream().sorted(Comparator.comparing(r -> r.namespace + "/" + r.kind + "/" + r.name)).toList();
        String revision = digest(mapper.writeValueAsString(ordered));
        Instant now = Instant.now();
        Snapshot next = new Snapshot(1, scope, revision, now.toString(), now.plusSeconds(ttlSeconds).toString(), ordered);
        byte[] bytes = mapper.writeValueAsBytes(next);
        if (bytes.length > 262_144) throw new IllegalArgumentException("Inventory exceeds local memory budget");
        Path temp = Files.createTempFile(file.getParent(), ".topology-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            snapshot = next;
        } finally { Files.deleteIfExists(temp); }
    }

    Map<String, Object> view(boolean enabled, boolean lastCollectionFailed) {
        Snapshot saved = snapshot;
        boolean fresh = enabled && saved != null && Instant.parse(saved.expiresAt).isAfter(Instant.now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "kubernetes-allowlisted-inventory");
        result.put("enabled", enabled); result.put("fresh", fresh); result.put("stale", enabled && saved != null && !fresh);
        result.put("resources", fresh ? saved.resources : List.of());
        result.put("revision", saved == null ? "unavailable" : saved.revision);
        result.put("collectedAt", saved == null ? "unavailable" : saved.collectedAt);
        result.put("expiresAt", saved == null ? "unavailable" : saved.expiresAt);
        List<String> gaps = new ArrayList<>();
        if (!enabled) gaps.add("Cluster collector is disabled; no live service topology is available.");
        else if (!fresh) gaps.add("No fresh cluster snapshot is available. Stale inventory is withheld from diagnosis.");
        if (lastCollectionFailed) gaps.add("Latest reconciliation failed or exceeded bounds; retry is scheduled.");
        gaps.add("Infrastructure presence/readiness does not prove a request path or a customer session cause.");
        result.put("gaps", gaps);
        return result;
    }

    private boolean valid(Snapshot saved) {
        if (saved == null || saved.schemaVersion != 1 || !scope.equals(saved.scope) || saved.resources == null
                || saved.resources.size() > 256 || saved.resources.stream().anyMatch(r -> !validResource(r))) return false;
        Instant collected = Instant.parse(saved.collectedAt), expires = Instant.parse(saved.expiresAt);
        return !collected.isAfter(Instant.now().plusSeconds(60)) && expires.equals(collected.plusSeconds(ttlSeconds))
                && saved.revision.equals(digest(mapper.writeValueAsString(saved.resources)));
    }

    static boolean validResource(Resource r) {
        return r != null && Set.of("Service", "Deployment", "StatefulSet", "EndpointSlice").contains(r.kind)
                && identifier(r.namespace) && identifier(r.name) && identifier(r.resourceVersion)
                && r.images != null && r.images.size() <= 10 && r.images.stream().allMatch(s -> s != null && s.length() <= 256
                && s.matches("[A-Za-z0-9][A-Za-z0-9._/:+-]*(?:@sha256:[a-f0-9]{64})?"))
                && r.version != null && r.version.length() <= 128 && r.version.matches("[A-Za-z0-9._+-]*")
                && r.serviceName != null && r.serviceName.length() <= 253 && r.serviceName.matches("[a-z0-9.-]*")
                && r.desired >= 0 && r.ready >= 0;
    }

    private static boolean identifier(String value) { return value != null && value.length() <= 253 && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*"); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    record Resource(String kind, String namespace, String name, String resourceVersion, String version,
                    String serviceName, List<String> images, int desired, int ready) {}
    record Snapshot(int schemaVersion, String scope, String revision, String collectedAt, String expiresAt, List<Resource> resources) {}
}
