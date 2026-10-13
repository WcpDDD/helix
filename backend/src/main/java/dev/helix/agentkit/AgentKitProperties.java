package dev.helix.agentkit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helix.agentkit")
public record AgentKitProperties(String workspace, String model, String baseUrl, String apiKey, Integer maxTurns) {

	public int turns() {
		return maxTurns == null || maxTurns < 1 ? 20 : maxTurns;
	}

	public boolean ready() {
		return present(model) && present(apiKey);
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
