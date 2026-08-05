package com.electrahub.aisupport.service;

import com.electrahub.aisupport.config.LocalAiRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class LocalModelWarmup {
    private static final Logger log = LoggerFactory.getLogger(LocalModelWarmup.class);

    private final LocalAiRuntimeProperties properties;
    private final RoutingLlmClient routingClient;

    LocalModelWarmup(LocalAiRuntimeProperties properties, RoutingLlmClient routingClient) {
        this.properties = properties;
        this.routingClient = routingClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!properties.warmupEnabled()) {
            return;
        }
        Thread.ofVirtual().name("local-ai-warmup").start(() -> {
            LlmClient.LlmCompletion result = routingClient.warmupOllama();
            if (!result.ok()) {
                log.warn("Local model warmup did not complete provider={} model={} error={}",
                        result.provider(), result.model(), result.error());
            }
        });
    }
}
