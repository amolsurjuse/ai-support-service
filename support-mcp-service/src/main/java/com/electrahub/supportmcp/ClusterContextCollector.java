package com.electrahub.supportmcp;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;

@Component
public class ClusterContextCollector {
    private final boolean enabled;
    private final URI api;
    private final List<String> namespaces;
    private final Path tokenPath, caPath;
    private final ObjectMapper mapper;
    private final ContextMemory memory;
    private final MeterRegistry metrics;
    private volatile boolean failed;

    public ClusterContextCollector(ObjectMapper mapper, ContextMemory memory, MeterRegistry metrics,
            @Value("${support-mcp.cluster.enabled}") boolean enabled, @Value("${support-mcp.cluster.api-url}") String api,
            @Value("${support-mcp.cluster.namespaces}") String namespaces,
            @Value("${support-mcp.cluster.token-path}") String tokenPath, @Value("${support-mcp.cluster.ca-path}") String caPath) {
        this.mapper = mapper; this.memory = memory; this.metrics = metrics; this.enabled = enabled;
        this.api = URI.create(api);
        this.namespaces = Arrays.stream(namespaces.split(",")).map(String::trim).distinct().sorted().toList();
        if (!"https".equals(this.api.getScheme()) || this.api.getHost() == null || this.api.getUserInfo() != null
                || this.api.getQuery() != null || this.api.getFragment() != null || !this.api.getPath().matches("/?"))
            throw new IllegalArgumentException("Kubernetes API must be a fixed HTTPS origin");
        if (this.namespaces.isEmpty() || this.namespaces.size() > 4 || this.namespaces.stream().anyMatch(n -> !n.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")))
            throw new IllegalArgumentException("Specify one to four explicit Kubernetes namespaces");
        this.tokenPath = Path.of(tokenPath); this.caPath = Path.of(caPath);
    }

    @Scheduled(initialDelay = 1000, fixedDelayString = "${support-mcp.cluster.reconcile-ms:60000}")
    public void reconcile() {
        if (!enabled) return;
        try {
            // Re-open projected files on every reconciliation so service account rotation is respected.
            String token = Files.readString(tokenPath).trim();
            if (token.isEmpty() || token.length() > 16_384 || token.contains("\n")) throw new IllegalStateException();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).sslContext(clusterTls())
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            List<ContextMemory.Resource> inventory = new ArrayList<>();
            for (String namespace : namespaces) {
                collect(client, token, "/api/v1/namespaces/" + namespace + "/services", "Service", namespace, inventory);
                collect(client, token, "/apis/apps/v1/namespaces/" + namespace + "/deployments", "Deployment", namespace, inventory);
                collect(client, token, "/apis/apps/v1/namespaces/" + namespace + "/statefulsets", "StatefulSet", namespace, inventory);
                collect(client, token, "/apis/discovery.k8s.io/v1/namespaces/" + namespace + "/endpointslices", "EndpointSlice", namespace, inventory);
            }
            // Publish only a complete bounded inventory. No partial reconciliation replaces the last good one.
            memory.publish(inventory); failed = false;
            metrics.counter("support.mcp.cluster.reconcile", "outcome", "success").increment();
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            failed = true;
            metrics.counter("support.mcp.cluster.reconcile", "outcome", "failed").increment();
            LoggerFactory.getLogger(getClass()).warn("Cluster context reconciliation unavailable; previous inventory will expire normally");
        }
    }

    Map<String, Object> topology() { return memory.view(enabled, failed); }

    private void collect(HttpClient client, String token, String path, String kind, String namespace,
                         List<ContextMemory.Resource> inventory) throws Exception {
        var request = HttpRequest.newBuilder(api.resolve(path + "?limit=256")).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        var response = BoundedHttp.send(client, request, 2_097_152);
            if (response.statusCode() != 200) throw new IllegalStateException();
            JsonNode list = mapper.readTree(response.body());
            if (!list.path("items").isArray() || !list.path("metadata").path("continue").asText("").isEmpty()) throw new IllegalStateException();
            for (JsonNode item : list.path("items")) {
                inventory.add(sanitize(item, kind, namespace));
                if (inventory.size() > 256) throw new IllegalStateException();
            }
    }

    static ContextMemory.Resource sanitize(JsonNode item, String kind, String namespace) {
        if (!kind.equals(item.path("kind").asText(kind)) || !namespace.equals(item.path("metadata").path("namespace").asText()))
            throw new IllegalArgumentException("Inventory resource scope mismatch");
        var metadata = item.path("metadata");
        List<String> images = new ArrayList<>();
        item.path("spec").path("template").path("spec").path("containers").forEach(container -> images.add(container.path("image").asText("")));
        int desired = item.path("spec").path("replicas").asInt(0), ready = item.path("status").path("readyReplicas").asInt(0);
        if (kind.equals("EndpointSlice")) {
            desired = item.path("endpoints").size(); ready = 0;
            for (JsonNode endpoint : item.path("endpoints")) if (endpoint.path("conditions").path("ready").asBoolean(false)) ready++;
        }
        String version = metadata.path("labels").path("app.kubernetes.io/version").asText("");
        String service = kind.equals("EndpointSlice") ? metadata.path("labels").path("kubernetes.io/service-name").asText("") : "";
        var safe = new ContextMemory.Resource(kind, namespace, metadata.path("name").asText(), metadata.path("resourceVersion").asText(),
                version, service, List.copyOf(images), desired, ready);
        if (!ContextMemory.validResource(safe)) throw new IllegalArgumentException("Inventory fields failed sanitization");
        return safe;
    }

    private SSLContext clusterTls() throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        var store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null);
        try (InputStream input = Files.newInputStream(caPath)) {
            int i = 0;
            for (var certificate : factory.generateCertificates(input)) store.setCertificateEntry("cluster-" + i++, certificate);
            if (i == 0) throw new IllegalStateException("Cluster CA is empty");
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, trust.getTrustManagers(), null); return ssl;
    }
}
