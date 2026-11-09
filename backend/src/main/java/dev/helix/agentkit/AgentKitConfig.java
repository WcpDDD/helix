package dev.helix.agentkit;

import dev.helix.mcp.McpProperties;
import dev.helix.wecom.WecomProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ AgentKitProperties.class, ObjectStorageProperties.class, WecomProperties.class,
		McpProperties.class })
public class AgentKitConfig {
}
