package dev.helix.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LoginBootstrap {

	private static final long SKEW = 30_000;

	private static final Logger log = LoggerFactory.getLogger(LoginBootstrap.class);

	private final LoginStore logins;

	private final GitlabOAuthClient oauth;

	private final GitlabSessionCredential credential;

	public LoginBootstrap(LoginStore logins, GitlabOAuthClient oauth, GitlabSessionCredential credential) {
		this.logins = logins;
		this.oauth = oauth;
		this.credential = credential;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void restore() {
		GitlabLoginEntity row = logins.latest().orElse(null);
		if (row == null || row.getAccessToken() == null || row.getAccessToken().isBlank()) {
			return;
		}
		if (row.getExpiresAt() > 0 && System.currentTimeMillis() + SKEW >= row.getExpiresAt()) {
			try {
				GitlabLogin refreshed = oauth.refresh(row.getRefreshToken());
				if (refreshed != null && refreshed.accessToken() != null && !refreshed.accessToken().isBlank()) {
					logins.updateTokens(row.getId(), refreshed.accessToken(), refreshed.refreshToken(),
							refreshed.expiresAt());
					credential.remember(refreshed.accessToken());
					return;
				}
			}
			catch (RuntimeException exception) {
				log.warn("保存的 GitLab 登录没有续上");
			}
		}
		credential.remember(row.getAccessToken());
	}

}
