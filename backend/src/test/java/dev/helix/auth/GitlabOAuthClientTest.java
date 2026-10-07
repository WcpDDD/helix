package dev.helix.auth;

import dev.helix.config.HelixProperties;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class GitlabOAuthClientTest {

	@Test
	void exchangeReadsTheUserAndKeepsTheTokenOffTheProfile() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabOAuthClient client = client(builder);

		server.expect(requestTo("https://hackers.oalite.com/oauth/token"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().string(containsString("grant_type=authorization_code")))
				.andExpect(content().string(containsString("client_id=test-client")))
				.andExpect(content().string(containsString("code=code-1")))
				.andRespond(withSuccess("{\"access_token\":\"tok\",\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://hackers.oalite.com/api/v4/user"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer tok"))
				.andRespond(withSuccess(
						"{\"id\":7,\"username\":\"ada\",\"name\":\"Ada\",\"avatar_url\":\"https://hackers.oalite.com/a.png\"}",
						MediaType.APPLICATION_JSON));

		GitlabLogin login = client.exchange("code-1");

		assertThat(login.user().username()).isEqualTo("ada");
		assertThat(login.user().name()).isEqualTo("Ada");
		assertThat(login.user().avatarUrl()).isEqualTo("https://hackers.oalite.com/a.png");
		assertThat(login.accessToken()).isEqualTo("tok");
		assertThat(login.user().toString()).doesNotContain("tok");
		server.verify();
	}

	@Test
	void exchangeReportsGitlabErrorDescription() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabOAuthClient client = client(builder);
		server.expect(requestTo("https://hackers.oalite.com/oauth/token"))
				.andRespond(withUnauthorizedRequest()
						.body("{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}")
						.contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.exchange("bad")).isInstanceOf(GitlabOAuthException.class)
				.hasMessage("Client authentication failed");
	}

	@Test
	void exchangeReportsWhenGitlabIsUnreachable() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabOAuthClient client = client(builder);
		server.expect(requestTo("https://hackers.oalite.com/oauth/token"))
				.andRespond(withException(new IOException("connection closed")));

		assertThatThrownBy(() -> client.exchange("code-1")).isInstanceOf(GitlabOAuthException.class)
				.hasMessage("连不上 GitLab");
	}

	private static GitlabOAuthClient client(RestClient.Builder builder) {
		HelixProperties.Gitlab gitlab = new HelixProperties.Gitlab("https://hackers.oalite.com/", "test-client", "test-secret",
				"http://127.0.0.1:5173/api/auth/gitlab/callback", "read_user");
		return new GitlabOAuthClient(gitlab, builder.build());
	}

}
