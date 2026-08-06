package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AiSupportProperties.class, LocalAiRuntimeProperties.class, TenantAiProperties.class})
class AiSupportConfig {
}
