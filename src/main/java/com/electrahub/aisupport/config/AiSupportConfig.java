package com.electrahub.aisupport.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AiSupportProperties.class, LocalAiRuntimeProperties.class, TenantAiProperties.class})
class AiSupportConfig {
}
