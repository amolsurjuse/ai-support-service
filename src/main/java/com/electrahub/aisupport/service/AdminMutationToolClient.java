package com.electrahub.aisupport.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class AdminMutationToolClient {
    private final RestClient gateway;

    @Autowired
    public AdminMutationToolClient(@Value("${electrahub.ai-support.gateway-url:http://api-gateway:8090}") String gatewayUrl,
                                   @Value("${electrahub.ai-support.admin-tool-timeout-ms:4000}") int timeoutMs) {
        this(RestClient.builder(), gatewayUrl, timeoutMs);
    }

    AdminMutationToolClient(RestClient.Builder builder, String gatewayUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.gateway = builder.baseUrl(gatewayUrl).requestFactory(requestFactory).build();
    }

    void execute(AdminMutationApprovalStore.PendingApproval approval, String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalArgumentException("An administrator bearer token is required.");
        }
        switch (approval.operation()) {
            case STOP_SESSION -> gateway.post()
                    .uri("/session/api/v1/sessions/admin/{id}/stop", approval.targetId())
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header("Idempotency-Key", approval.idempotencyKey())
                    .body(Map.of("reason", "AI_ADMIN_APPROVED", "userInitiated", false))
                    .retrieve()
                    .toBodilessEntity();
        }
    }
}
