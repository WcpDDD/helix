package dev.helix.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helix")
public record HelixProperties(String frontendUrl, Duration alignInterval, String boardFile, Gitlab gitlab) {

	public record Gitlab(String baseUrl, String clientId, String clientSecret, String redirectUri, String scope) {

		public boolean configured() {
			return present(clientId) && present(clientSecret);
		}

		public String origin() {
			String url = baseUrl == null ? "" : baseUrl.trim();
			while (url.endsWith("/")) {
				url = url.substring(0, url.length() - 1);
			}
			return url;
		}

		private static boolean present(String value) {
			return value != null && !value.isBlank();
		}

	}

}
