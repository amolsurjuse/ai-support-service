package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.AiSupportProperties;
import com.electrahub.aisupport.model.ChatDtos.ContextPayload;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class BackendDiagnosticsClient {
    private static final Logger log = LoggerFactory.getLogger(BackendDiagnosticsClient.class);

    private final AiSupportProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService diagnosticsExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BackendDiagnosticsClient(AiSupportProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    public DiagnosticsSnapshot collect(ContextPayload context, String authorization) {
        return collect(null, context, authorization);
    }

    public DiagnosticsSnapshot collect(String userMessage, ContextPayload context, String authorization) {
        ContextPayload safeContext = context == null
                ? new ContextPayload(null, null, null, null, null, null, null, "driver")
                : context;
        String message = userMessage == null ? null : userMessage.toLowerCase(Locale.ROOT);
        List<NamedDiagnosticTask> tasks = new ArrayList<>();
        if (message == null || containsAny(message, "payment", "wallet", "card", "balance", "receipt", "billing", "cost", "price", "fee", "refund", "hold")) {
            tasks.add(task("payment", (facts, gaps) -> readPaymentState(authorization, facts, gaps)));
        }
        if (message == null || !isBlank(safeContext.sessionId())
                || containsAny(message, "session", "charging", "start", "stop", "stuck", "preparing", "active", "idle", "receipt", "meter", "energy")) {
            tasks.add(task("session", (facts, gaps) -> readSessionState(safeContext, authorization, facts, gaps)));
        }
        if (message == null || containsAny(message, "charger", "connector", "available", "availability", "online", "offline", "heartbeat", "start", "charging", "power", "station")) {
            tasks.add(task("charger", (facts, gaps) -> readChargerState(safeContext, facts, gaps)));
        }
        if (message == null || containsAny(message, "ocpp", "online", "offline", "heartbeat", "start fail", "failed to start", "stuck", "preparing", "simulator", "unplug")) {
            tasks.add(task("ocpp connection", (facts, gaps) -> readOcppConnection(safeContext, facts, gaps)));
            tasks.add(task("ocpp history", (facts, gaps) -> readOcppHistory(safeContext, facts, gaps)));
        }

        if (tasks.isEmpty()) {
            log.info("Sparky diagnostics skipped because the question does not require live backend state");
            return new DiagnosticsSnapshot(List.of(), List.of());
        }
        log.info("Sparky diagnostics selected tasks={} questionChars={}",
                tasks.stream().map(NamedDiagnosticTask::name).toList(), message == null ? 0 : message.length());

        boolean timedOut = false;
        try {
            CompletableFuture.allOf(tasks.stream().map(NamedDiagnosticTask::future).toArray(CompletableFuture[]::new))
                    .get(Math.max(250, properties.diagnosticsTotalTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            timedOut = true;
            log.info("Sparky diagnostics reached total timeoutMs={}", properties.diagnosticsTotalTimeoutMs());
        } catch (Exception ex) {
            log.warn("Sparky diagnostics completed with an execution error type={}", ex.getClass().getSimpleName());
        }

        List<String> facts = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (NamedDiagnosticTask task : tasks) {
            DiagnosticSection section = task.future().getNow(null);
            if (section != null) {
                facts.addAll(section.facts());
                gaps.addAll(section.gaps());
            } else {
                task.future().cancel(true);
                gaps.add(task.name() + " diagnostics did not complete before the response deadline");
            }
        }
        if (timedOut) {
            gaps.add("some live diagnostics exceeded the response deadline");
        }

        return new DiagnosticsSnapshot(List.copyOf(facts), List.copyOf(gaps));
    }

    @PreDestroy
    void stopDiagnosticsExecutor() {
        diagnosticsExecutor.close();
    }

    private NamedDiagnosticTask task(String name, DiagnosticReader reader) {
        return new NamedDiagnosticTask(name, CompletableFuture.supplyAsync(() -> {
            List<String> facts = new ArrayList<>();
            List<String> gaps = new ArrayList<>();
            try {
                reader.collect(facts, gaps);
            } catch (RuntimeException ex) {
                log.warn("Sparky {} diagnostic failed type={}", name, ex.getClass().getSimpleName());
                gaps.add(name + " diagnostics failed");
            }
            return new DiagnosticSection(List.copyOf(facts), List.copyOf(gaps));
        }, diagnosticsExecutor));
    }

    public ChargerAlternatives findChargerAlternatives(ContextPayload context, String userMessage, int limit) {
        ContextPayload safeContext = context == null
                ? new ContextPayload(null, null, null, null, null, null, null, "driver")
                : context;
        String requestedStandard = requestedConnectorStandard(userMessage);
        Optional<ReferencePoint> reference = readReferencePoint(safeContext);
        List<ChargerAlternative> alternatives = readAvailableAlternatives(safeContext, requestedStandard, reference)
                .stream()
                .sorted(Comparator
                        .comparing((ChargerAlternative alternative) -> alternative.distanceMiles() == null
                                ? Double.MAX_VALUE
                                : alternative.distanceMiles())
                        .thenComparing(ChargerAlternative::chargerId)
                        .thenComparing(ChargerAlternative::connectorId))
                .limit(Math.max(1, limit))
                .toList();
        return new ChargerAlternatives(
                connectorLabel(requestedStandard),
                reference.map(ReferencePoint::locationLabel).orElse(""),
                alternatives
        );
    }

    private void readPaymentState(String authorization, List<String> facts, List<String> gaps) {
        if (isBlank(authorization)) {
            gaps.add("payment state skipped because the request did not include a bearer token");
            return;
        }
        get(properties.paymentServiceUrl(), "/api/v1/payment/state", authorization)
                .ifPresentOrElse(json -> {
                    JsonNode wallet = json.path("wallet");
                    if (!wallet.isMissingNode()) {
                        facts.add("wallet balance is " + money(wallet.path("currency").asText("USD"), wallet.path("balance")));
                        facts.add("wallet budget is " + money(wallet.path("currency").asText("USD"), wallet.path("budget")));
                    }
                    facts.add("saved payment cards: " + json.path("cards").size());
                    facts.add("auto top-up: " + enabledLabel(json.path("autoTopUp").path("enabled")));
                }, () -> gaps.add("payment state was not reachable"));
    }

    private void readSessionState(ContextPayload context, String authorization, List<String> facts, List<String> gaps) {
        if (isBlank(authorization)) {
            gaps.add("active session lookup skipped because the request did not include a bearer token");
            return;
        }
        if (!isBlank(context.sessionId())) {
            get(properties.sessionServiceUrl(), "/api/v1/sessions/" + encode(context.sessionId()) + "/current", authorization)
                    .ifPresentOrElse(session -> describeActiveSession("current session", session, facts),
                            () -> gaps.add("current session " + context.sessionId() + " was not reachable"));
            readMeterValues(context.sessionId(), authorization, facts, gaps);
            return;
        }

        get(properties.sessionServiceUrl(), "/api/v1/sessions/active", authorization)
                .ifPresentOrElse(json -> {
                    if (json.isArray()) {
                        facts.add("active sessions for this driver: " + json.size());
                        firstMatchingSession(json, context).ifPresent(session -> describeActiveSession("matching active session", session, facts));
                    } else {
                        facts.add("active session lookup returned " + json.path("status").asText("a response"));
                    }
                }, () -> gaps.add("active session lookup was not reachable"));
    }

    private void readMeterValues(String sessionId, String authorization, List<String> facts, List<String> gaps) {
        get(properties.sessionServiceUrl(), "/api/v1/meter-values/session/" + encode(sessionId), authorization)
                .ifPresentOrElse(json -> {
                    if (!json.isArray() || json.isEmpty()) {
                        facts.add("no stored meter values were found for session " + sessionId);
                        return;
                    }
                    latestNode(json).ifPresent(latest -> facts.add("latest meter value: "
                            + latest.path("measurand").asText("unknown")
                            + "=" + latest.path("value").asText("--")
                            + " " + latest.path("unit").asText("")
                            + " at " + latest.path("timestamp").asText("--")));
                }, () -> gaps.add("meter value lookup was not reachable"));
    }

    private void readChargerState(ContextPayload context, List<String> facts, List<String> gaps) {
        if (isBlank(context.chargerId()) && isBlank(context.connectorId())) {
            gaps.add("charger lookup skipped because no charger or connector context was provided");
            return;
        }

        ObjectNode variables = objectMapper.createObjectNode();
        if (!isBlank(context.chargerId())) {
            variables.put("chargerId", context.chargerId());
        }
        if (!isBlank(context.connectorId())) {
            variables.put("connectorId", context.connectorId());
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", """
                query($chargerId: String, $connectorId: String) {
                  ocpiCharger(chargerId: $chargerId, connectorId: $connectorId) {
                    chargerId chargerName status available availablePorts busyPorts lastUpdated
                    currentSession { id status startedAt userId }
                    location { ocpiLocationId name city }
                    evses {
                      status
                      connectors { id status available standard powerType maxPowerKw }
                    }
                  }
                }
                """);
        body.set("variables", variables);

        post(properties.chargerServiceUrl(), "/graphql", body.toString(), null)
                .map(json -> json.path("data").path("ocpiCharger"))
                .filter(JsonNode::isObject)
                .ifPresentOrElse(charger -> {
                    facts.add("charger " + charger.path("chargerId").asText("--")
                            + " status is " + charger.path("status").asText("UNKNOWN")
                            + " with " + charger.path("availablePorts").asInt(0)
                            + " available port(s) and " + charger.path("busyPorts").asInt(0) + " busy port(s)");
                    JsonNode currentSession = charger.path("currentSession");
                    if (currentSession.isObject()) {
                        facts.add("charger current session is " + currentSession.path("id").asText("--")
                                + " in " + currentSession.path("status").asText("--"));
                    }
                    findConnector(charger, context.connectorId()).ifPresent(connector -> facts.add("connector "
                            + connector.path("id").asText("--") + " is "
                            + connector.path("status").asText("UNKNOWN")
                            + " available=" + connector.path("available").asBoolean(false)
                            + " power=" + decimal(connector.path("maxPowerKw")) + " kW"));
                }, () -> gaps.add("charger status lookup was not reachable"));
    }

    private void readOcppConnection(ContextPayload context, List<String> facts, List<String> gaps) {
        if (isBlank(context.chargerId())) {
            gaps.add("OCPP connection lookup skipped because no charger id was provided");
            return;
        }
        get(properties.ocppServiceUrl(), "/api/v1/ocpp/connections/" + encode(context.chargerId()), null)
                .ifPresentOrElse(json -> {
                    facts.add("OCPP connection active=" + json.path("active").asBoolean(false)
                            + ", localConnected=" + json.path("localConnected").asBoolean(false)
                            + ", databaseActive=" + json.path("databaseActive").asBoolean(false));
                    String heartbeat = json.path("lastHeartbeatAt").asText("");
                    if (!isBlank(heartbeat)) {
                        facts.add("last OCPP heartbeat was " + ageLabel(heartbeat) + " ago at " + heartbeat);
                    } else {
                        facts.add("no OCPP heartbeat timestamp is recorded for this charger");
                    }
                }, () -> gaps.add("OCPP connection lookup did not find an active connection for " + context.chargerId()));
    }

    private void readOcppHistory(ContextPayload context, List<String> facts, List<String> gaps) {
        if (isBlank(context.chargerId())) {
            return;
        }
        get(properties.ocppServiceUrl(), "/api/v1/ocpp/stats/connections/history?size=8&chargePointId=" + encode(context.chargerId()), null)
                .ifPresentOrElse(json -> {
                    JsonNode content = json.path("content");
                    if (!content.isArray() || content.isEmpty()) {
                        facts.add("no recent OCPP messages were found for " + context.chargerId());
                        return;
                    }
                    List<String> actions = new ArrayList<>();
                    JsonNode latestDms = null;
                    for (JsonNode message : content) {
                        String action = message.path("action").asText("");
                        if (!isBlank(action)) {
                            actions.add(action + "/" + message.path("direction").asText("?") + "/" + message.path("status").asText("logged"));
                        }
                        if (latestDms == null && ("DataTransfer".equalsIgnoreCase(action) || "MeterValues".equalsIgnoreCase(action))) {
                            latestDms = message;
                        }
                    }
                    if (!actions.isEmpty()) {
                        facts.add("recent OCPP actions: " + String.join(", ", actions.stream().limit(5).toList()));
                    }
                    if (latestDms != null) {
                        facts.add("latest telemetry-like OCPP event is " + latestDms.path("action").asText("--")
                                + " at " + latestDms.path("createdAt").asText("--"));
                    }
                }, () -> gaps.add("recent OCPP message history was not reachable"));
    }

    private Optional<ReferencePoint> readReferencePoint(ContextPayload context) {
        if (isBlank(context.chargerId()) && isBlank(context.locationId())) {
            return Optional.empty();
        }

        ObjectNode variables = objectMapper.createObjectNode();
        if (!isBlank(context.chargerId())) {
            variables.put("chargerId", context.chargerId());
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", """
                query($chargerId: String) {
                  ocpiCharger(chargerId: $chargerId) {
                    chargerId chargerName
                    location {
                      ocpiLocationId name city
                      coordinates { latitude longitude }
                    }
                  }
                }
                """);
        body.set("variables", variables);

        return post(properties.chargerServiceUrl(), "/graphql", body.toString(), null)
                .map(json -> json.path("data").path("ocpiCharger"))
                .filter(JsonNode::isObject)
                .map(charger -> {
                    JsonNode location = charger.path("location");
                    JsonNode coordinates = location.path("coordinates");
                    Double latitude = numberOrNull(coordinates.path("latitude"));
                    Double longitude = numberOrNull(coordinates.path("longitude"));
                    String label = firstNonBlank(
                            location.path("name").asText(""),
                            location.path("ocpiLocationId").asText(""),
                            context.locationId(),
                            charger.path("chargerName").asText(""),
                            charger.path("chargerId").asText("")
                    );
                    return new ReferencePoint(label, latitude, longitude);
                });
    }

    private List<ChargerAlternative> readAvailableAlternatives(ContextPayload context,
                                                              String requestedStandard,
                                                              Optional<ReferencePoint> reference) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("countryCode", "US");
        variables.put("limit", 500);
        variables.put("offset", 0);
        reference.ifPresent(point -> {
            if (point.latitude() != null && point.longitude() != null) {
                variables.put("latitude", point.latitude());
                variables.put("longitude", point.longitude());
                variables.put("radiusKm", 80.0);
            }
        });

        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", """
                query($countryCode: String, $limit: Int, $offset: Int, $latitude: Float, $longitude: Float, $radiusKm: Float) {
                  ocpiChargers(countryCode: $countryCode, limit: $limit, offset: $offset, latitude: $latitude, longitude: $longitude, radiusKm: $radiusKm) {
                    chargerId chargerName status availablePorts busyPorts
                    location {
                      ocpiLocationId name city
                      coordinates { latitude longitude }
                    }
                    evses {
                      uid status
                      connectors { id status available standard powerType maxPowerKw }
                    }
                  }
                }
                """);
        body.set("variables", variables);

        return post(properties.chargerServiceUrl(), "/graphql", body.toString(), null)
                .map(json -> json.path("data").path("ocpiChargers"))
                .filter(JsonNode::isArray)
                .map(chargers -> {
                    List<ChargerAlternative> results = new ArrayList<>();
                    for (JsonNode charger : chargers) {
                        String chargerId = charger.path("chargerId").asText("");
                        if (!isBlank(context.chargerId()) && context.chargerId().equalsIgnoreCase(chargerId)) {
                            continue;
                        }
                        JsonNode location = charger.path("location");
                        JsonNode coordinates = location.path("coordinates");
                        Double chargerLatitude = numberOrNull(coordinates.path("latitude"));
                        Double chargerLongitude = numberOrNull(coordinates.path("longitude"));
                        Double distanceMiles = distanceMiles(reference, chargerLatitude, chargerLongitude);
                        String locationName = firstNonBlank(
                                location.path("name").asText(""),
                                location.path("ocpiLocationId").asText(""),
                                location.path("city").asText(""),
                                "unknown location"
                        );
                        for (JsonNode evse : charger.path("evses")) {
                            for (JsonNode connector : evse.path("connectors")) {
                                if (!connectorMatches(connector, requestedStandard)) {
                                    continue;
                                }
                                if (!connector.path("available").asBoolean(false)
                                        || !"AVAILABLE".equalsIgnoreCase(connector.path("status").asText(""))) {
                                    continue;
                                }
                                results.add(new ChargerAlternative(
                                        chargerId,
                                        firstNonBlank(charger.path("chargerName").asText(""), chargerId),
                                        connector.path("id").asText("--"),
                                        locationName,
                                        distanceMiles,
                                        distanceMiles == null ? "" : "%.1f mi".formatted(distanceMiles),
                                        powerLabel(connector)
                                ));
                            }
                        }
                    }
                    return results;
                })
                .orElseGet(List::of);
    }

    private Optional<JsonNode> get(String baseUrl, String path, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + path))
                .timeout(timeout())
                .GET()
                .header("Accept", "application/json");
        addAuthorization(builder, authorization);
        return send(builder.build());
    }

    private Optional<JsonNode> post(String baseUrl, String path, String body, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + path))
                .timeout(timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        addAuthorization(builder, authorization);
        return send(builder.build());
    }

    private Optional<JsonNode> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || isBlank(response.body())) {
                log.debug("Diagnostic request {} returned status {}", request.uri(), response.statusCode());
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (IOException ex) {
            log.debug("Diagnostic request {} failed: {}", request.uri(), ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Diagnostic request {} interrupted", request.uri());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.debug("Diagnostic request {} failed: {}", request.uri(), ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> firstMatchingSession(JsonNode sessions, ContextPayload context) {
        for (JsonNode session : sessions) {
            if (!isBlank(context.sessionId()) && context.sessionId().equalsIgnoreCase(session.path("id").asText())) {
                return Optional.of(session);
            }
            if (!isBlank(context.connectorId()) && context.connectorId().equalsIgnoreCase(session.path("connectorId").asText())) {
                return Optional.of(session);
            }
        }
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    private Optional<JsonNode> findConnector(JsonNode charger, String connectorId) {
        if (isBlank(connectorId)) {
            return Optional.empty();
        }
        for (JsonNode evse : charger.path("evses")) {
            for (JsonNode connector : evse.path("connectors")) {
                if (connectorId.equalsIgnoreCase(connector.path("id").asText())) {
                    return Optional.of(connector);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> latestNode(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values.stream()
                .max(Comparator.comparing(node -> node.path("timestamp").asText("")));
    }

    private void describeActiveSession(String label, JsonNode session, List<String> facts) {
        facts.add(label + " " + session.path("id").asText("--")
                + " is " + session.path("status").asText("--")
                + ", power=" + decimal(session.path("currentPowerKw")) + " kW"
                + ", energy=" + decimal(session.path("energyDeliveredKwh")) + " kWh"
                + ", estimated cost=" + money("USD", session.path("estimatedCost")));
    }

    private void addAuthorization(HttpRequest.Builder builder, String authorization) {
        if (!isBlank(authorization)) {
            builder.header("Authorization", authorization);
        }
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(500, properties.diagnosticsTimeoutMs()));
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requestedConnectorStandard(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("chademo")) {
            return "CHADEMO";
        }
        if (normalized.contains("ccs2")) {
            return "CCS2";
        }
        if (normalized.contains("ccs1")) {
            return "CCS1";
        }
        if (normalized.contains("ccs")) {
            return "CCS";
        }
        return "CCS";
    }

    private static boolean connectorMatches(JsonNode connector, String requestedStandard) {
        String standard = connector.path("standard").asText("").toUpperCase(Locale.ROOT);
        String requested = requestedStandard == null ? "" : requestedStandard.toUpperCase(Locale.ROOT);
        if ("CCS".equals(requested)) {
            return standard.contains("CCS");
        }
        return standard.equals(requested);
    }

    private static String connectorLabel(String requestedStandard) {
        if (isBlank(requestedStandard)) {
            return "CCS";
        }
        return requestedStandard.toUpperCase(Locale.ROOT);
    }

    private static String powerLabel(JsonNode connector) {
        String power = decimal(connector.path("maxPowerKw"));
        if ("--".equals(power)) {
            return "";
        }
        String standard = connector.path("standard").asText("");
        if (isBlank(standard)) {
            return power + " kW";
        }
        return standard + " / " + power + " kW";
    }

    private static Double numberOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Double.parseDouble(node.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double distanceMiles(Optional<ReferencePoint> reference, Double latitude, Double longitude) {
        if (reference.isEmpty()
                || reference.get().latitude() == null
                || reference.get().longitude() == null
                || latitude == null
                || longitude == null) {
            return null;
        }
        double earthRadiusMiles = 3958.7613;
        double lat1 = Math.toRadians(reference.get().latitude());
        double lat2 = Math.toRadians(latitude);
        double deltaLat = Math.toRadians(latitude - reference.get().latitude());
        double deltaLon = Math.toRadians(longitude - reference.get().longitude());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMiles * c;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String enabledLabel(JsonNode node) {
        return node.isBoolean() && node.asBoolean() ? "enabled" : "disabled";
    }

    private static String decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "--";
        }
        try {
            return new BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return node.asText("--");
        }
    }

    private static String money(String currency, JsonNode amount) {
        return (isBlank(currency) ? "USD" : currency.toUpperCase(Locale.ROOT)) + " " + decimal(amount);
    }

    private static String ageLabel(String isoInstant) {
        try {
            Duration age = Duration.between(Instant.parse(isoInstant), Instant.now());
            long seconds = Math.max(0, age.toSeconds());
            if (seconds < 60) {
                return seconds + "s";
            }
            long minutes = seconds / 60;
            if (minutes < 60) {
                return minutes + "m";
            }
            return (minutes / 60) + "h";
        } catch (DateTimeParseException ex) {
            return "unknown time";
        }
    }

    public record DiagnosticsSnapshot(List<String> facts, List<String> gaps) {
        public boolean hasFacts() {
            return !facts.isEmpty();
        }

        public String toAnswerText() {
            StringBuilder builder = new StringBuilder();
            if (!facts.isEmpty()) {
                builder.append("Live backend checks:\n");
                facts.stream().limit(12).forEach(fact -> builder.append("- ").append(fact).append('\n'));
            }
            if (!gaps.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append("Checks I could not complete:\n");
                gaps.stream().limit(5).forEach(gap -> builder.append("- ").append(gap).append('\n'));
            }
            return builder.toString().trim();
        }
    }

    @FunctionalInterface
    private interface DiagnosticReader {
        void collect(List<String> facts, List<String> gaps);
    }

    private record NamedDiagnosticTask(String name, CompletableFuture<DiagnosticSection> future) {
    }

    private record DiagnosticSection(List<String> facts, List<String> gaps) {
    }

    private record ReferencePoint(String locationLabel, Double latitude, Double longitude) {
    }

    public record ChargerAlternatives(String connectorLabel,
                                      String referenceLocation,
                                      List<ChargerAlternative> alternatives) {
    }

    public record ChargerAlternative(String chargerId,
                                     String chargerName,
                                     String connectorId,
                                     String locationName,
                                     Double distanceMiles,
                                     String distanceLabel,
                                     String powerLabel) {
    }
}
