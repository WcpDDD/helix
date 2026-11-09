package dev.helix.auth;

import java.util.Map;

import dev.helix.config.HelixProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitlabGitEnvTest {

	@Test
	void blankTokenAddsNothing() {
		assertThat(GitlabGitEnv.env("  ", "https://hackers.oalite.com")).isEmpty();
	}

	@Test
	void configuredPatWinsOverTheLastLogin() {
		HelixProperties properties = new HelixProperties(null, null, null, null, null, null,
				new HelixProperties.Gitlab("https://hackers.oalite.com", "", "", "", "", "agent-pat"));
		GitlabSessionCredential credential = new GitlabSessionCredential();
		credential.remember("someone-else");

		assertThat(GitlabGitEnv.offline(properties, credential)).isEqualTo("agent-pat");
	}

	@Test
	void tokenBecomesAHostScopedGitHelper() {
		Map<String, String> env = GitlabGitEnv.env("oauth-token", "https://hackers.oalite.com");

		assertThat(env.get("GITLAB_TOKEN")).isEqualTo("oauth-token");
		assertThat(env.get("HELIX_GIT_TOKEN")).isEqualTo("oauth-token");
		assertThat(env.get("GIT_CONFIG_KEY_0")).isEqualTo("credential.https://hackers.oalite.com.helper");
		assertThat(env.get("GIT_CONFIG_VALUE_0")).contains("$HELIX_GIT_TOKEN").doesNotContain("oauth-token");
		assertThat(env.get("GIT_TERMINAL_PROMPT")).isEqualTo("0");
	}

}
