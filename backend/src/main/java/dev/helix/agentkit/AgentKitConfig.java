package dev.helix.agentkit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentKitProperties.class)
public class AgentKitConfig {
}
