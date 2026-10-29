package dev.helix.agentkit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helix.agentkit")
public record AgentKitProperties(String workspace, String model, String baseUrl, String apiKey, Integer maxTurns,
		String visionModel) {

	public int turns() {
		// 0 表示不限制轮次。agent-kit 只在 maxTurns > 0 时截断。
		return maxTurns == null || maxTurns < 1 ? 0 : maxTurns;
	}

	public boolean ready() {
		return present(model) && present(apiKey);
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
