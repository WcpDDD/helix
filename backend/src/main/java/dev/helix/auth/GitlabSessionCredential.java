package dev.helix.auth;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class GitlabSessionCredential {

	private final AtomicReference<String> token = new AtomicReference<>();

	public void remember(String accessToken) {
		if (accessToken != null && !accessToken.isBlank()) {
			token.set(accessToken);
		}
	}

	public void forget(String accessToken) {
		if (accessToken != null) {
			token.compareAndSet(accessToken, null);
		}
	}

	public String current() {
		return token.get();
	}

}
