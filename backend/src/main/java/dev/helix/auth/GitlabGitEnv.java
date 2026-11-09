package dev.helix.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.helix.config.HelixProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class GitlabGitEnv {

	private static final long SKEW = 30_000;

	private static final String HELPER = "!f() { echo \"username=oauth2\"; echo \"password=$HELIX_GIT_TOKEN\"; }; f";

	private static final Logger log = LoggerFactory.getLogger(GitlabGitEnv.class);

	private final GitlabLoginRepository logins;

	private final GitlabOAuthClient oauth;

	private final LoginStore store;

	private final GitlabSessionCredential credential;

	private final HelixProperties properties;

	public GitlabGitEnv(GitlabLoginRepository logins, GitlabOAuthClient oauth, LoginStore store,
			GitlabSessionCredential credential, HelixProperties properties) {
		this.logins = logins;
		this.oauth = oauth;
		this.store = store;
		this.credential = credential;
		this.properties = properties;
	}

	@PostConstruct
	void noteOfflineToken() {
		if (offline().isBlank()) {
			log.warn("离线任务还没有配置 GitLab PAT");
			return;
		}
		log.info("离线任务的提交和拉取使用已配置的 GitLab PAT");
	}

	public Map<String, String> forUser(long gitlabUserId) {
		if (gitlabUserId <= 0) {
			return Map.of();
		}
		GitlabLoginEntity row = logins.findByGitlabUserId(gitlabUserId).orElse(null);
		if (row == null) {
			return Map.of();
		}
		return env(fresh(row), origin());
	}

	public Map<String, String> current() {
		return env(offline(properties, credential), origin());
	}

	public String offline() {
		return offline(properties, credential);
	}

	public static String offline(HelixProperties properties, GitlabSessionCredential credential) {
		if (properties != null && properties.gitlab() != null) {
			String configured = properties.gitlab().agentToken();
			if (configured != null && !configured.isBlank()) {
				return configured.strip();
			}
		}
		String current = credential == null ? null : credential.current();
		return current == null ? "" : current;
	}

	static Map<String, String> env(String accessToken, String origin) {
		if (accessToken == null || accessToken.isBlank()) {
			return Map.of();
		}
		Map<String, String> values = new LinkedHashMap<>();
		values.put("GITLAB_TOKEN", accessToken);
		values.put("GITLAB_PRIVATE_TOKEN", accessToken);
		values.put("HELIX_GIT_TOKEN", accessToken);
		values.put("GIT_TERMINAL_PROMPT", "0");
		if (origin != null && !origin.isBlank()) {
			values.put("GIT_CONFIG_COUNT", "1");
			values.put("GIT_CONFIG_KEY_0", "credential." + origin + ".helper");
			values.put("GIT_CONFIG_VALUE_0", HELPER);
		}
		return Map.copyOf(values);
	}

	private String fresh(GitlabLoginEntity row) {
		if (row.getExpiresAt() <= 0 || System.currentTimeMillis() + SKEW < row.getExpiresAt()) {
			return row.getAccessToken();
		}
		if (row.getRefreshToken() == null || row.getRefreshToken().isBlank()) {
			return row.getAccessToken();
		}
		try {
			GitlabLogin refreshed = oauth.refresh(row.getRefreshToken());
			if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
				return row.getAccessToken();
			}
			store.updateTokens(row.getId(), refreshed.accessToken(), refreshed.refreshToken(), refreshed.expiresAt());
			credential.remember(refreshed.accessToken());
			return refreshed.accessToken();
		}
		catch (RuntimeException exception) {
			log.warn("GitLab 登录没有续上");
			return row.getAccessToken();
		}
	}

	private String origin() {
		if (properties == null || properties.gitlab() == null) {
			return "";
		}
		return properties.gitlab().origin();
	}

}
