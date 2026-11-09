package dev.helix.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helix.mcp")
public record McpProperties(String token) {

	public boolean configured() {
		return token != null && !token.isBlank();
	}

	public String bearer() {
		return token == null ? "" : token.strip();
	}

}
