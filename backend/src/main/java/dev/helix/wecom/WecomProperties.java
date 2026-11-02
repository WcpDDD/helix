package dev.helix.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helix.wecom")
public record WecomProperties(String botId, String secret, String username) {

	public boolean enabled() {
		return present(botId) && present(secret);
	}

	public String user() {
		return username == null ? "" : username.strip();
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
