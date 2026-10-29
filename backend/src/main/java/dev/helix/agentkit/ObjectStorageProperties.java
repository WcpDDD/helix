package dev.helix.agentkit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helix.objects")
public record ObjectStorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey,
		String prefix) {

	public boolean ready() {
		return present(endpoint) && present(bucket) && present(accessKey) && present(secretKey);
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
