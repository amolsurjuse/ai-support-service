package com.electrahub.supportmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContextMemoryTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final ContextMemory.Resource resource = new ContextMemory.Resource("Deployment", "electrahub", "session-service", "42", "v1", "", List.of("registry/session:v1"), 2, 2);
    ContextMemory memory(String cluster) { return new ContextMemory(mapper, directory.toString(), cluster, "electrahub", 180); }

    @Test void restoredVersionedSnapshotIsFastButScopeChangesInvalidateIt() throws Exception {
        var first = memory("one"); first.publish(List.of(resource));
        var restored = memory("one").view(true, false);
        assertThat(restored.get("fresh")).isEqualTo(true);
        assertThat(restored.get("resources")).isEqualTo(List.of(resource));
        assertThat(memory("two").view(true, false).get("fresh")).isEqualTo(false);
        assertThat(first.view(false, false).get("resources")).isEqualTo(List.of());
    }

    @Test void expiredSnapshotIsWithheldAndFailedRefreshDoesNotExtendExpiry() throws Exception {
        memory("one").publish(List.of(resource));
        Path file = directory.resolve("topology-v1.json");
        var saved = mapper.readValue(Files.readAllBytes(file), ContextMemory.Snapshot.class);
        var old = new ContextMemory.Snapshot(1, saved.scope(), saved.revision(), Instant.now().minusSeconds(190).toString(),
                Instant.now().minusSeconds(190).plusSeconds(180).toString(), saved.resources());
        // Preserve the exact time relation so the snapshot is valid but expired.
        old = new ContextMemory.Snapshot(1, old.scope(), old.revision(), old.collectedAt(), Instant.parse(old.collectedAt()).plusSeconds(180).toString(), old.resources());
        Files.write(file, mapper.writeValueAsBytes(old));
        var view = memory("one").view(true, true);
        assertThat(view.get("fresh")).isEqualTo(false); assertThat(view.get("stale")).isEqualTo(true);
        assertThat(view.get("resources")).isEqualTo(List.of()); assertThat(view.get("expiresAt")).isEqualTo(old.expiresAt());
    }

    @Test void corruptMemoryAndInvalidResourcesNeverReachRetrieval() throws Exception {
        Files.writeString(directory.resolve("topology-v1.json"), "not-json");
        var memory = memory("one");
        assertThat(memory.view(true, false).get("resources")).isEqualTo(List.of());
        assertThatThrownBy(() -> memory.publish(List.of(new ContextMemory.Resource("Secret", "electrahub", "credentials", "1", "", "", List.of(), 0, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void inventoryProjectionDropsSecretsEnvironmentAnnotationsAndEndpointAddresses() {
        var input = mapper.readTree("""
                {"kind":"Deployment","metadata":{"name":"session-service","namespace":"electrahub","resourceVersion":"123","annotations":{"token":"secret-a"},"labels":{"app.kubernetes.io/version":"v2","private":"secret-b"}},"spec":{"replicas":2,"template":{"spec":{"containers":[{"image":"registry/session:v2","env":[{"name":"PASSWORD","value":"secret-c"}]}]}}},"status":{"readyReplicas":1}}
                """);
        var projected = ClusterContextCollector.sanitize(input, "Deployment", "electrahub");
        assertThat(projected.ready()).isEqualTo(1);
        assertThat(mapper.writeValueAsString(projected)).doesNotContain("secret-", "PASSWORD", "annotations", "env");
        var endpoint = mapper.readTree("""
                {"kind":"EndpointSlice","metadata":{"name":"session-abc","namespace":"electrahub","resourceVersion":"99","labels":{"kubernetes.io/service-name":"session"}},"endpoints":[{"addresses":["10.0.0.5"],"conditions":{"ready":true}}]}
                """);
        var safeEndpoint = ClusterContextCollector.sanitize(endpoint, "EndpointSlice", "electrahub");
        assertThat(safeEndpoint.ready()).isEqualTo(1); assertThat(safeEndpoint.serviceName()).isEqualTo("session");
        assertThat(mapper.writeValueAsString(safeEndpoint)).doesNotContain("10.0.0.5");
        assertThatThrownBy(() -> ClusterContextCollector.sanitize(input, "Deployment", "other-namespace")).isInstanceOf(IllegalArgumentException.class);
    }
}
