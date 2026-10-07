package dev.helix.auth;

import java.net.URI;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"helix.gitlab.base-url=https://hackers.oalite.com",
		"helix.gitlab.client-id=test-client",
		"helix.gitlab.client-secret=test-secret",
		"helix.gitlab.redirect-uri=http://127.0.0.1:5173/api/auth/gitlab/callback",
		"helix.gitlab.scope=read_user",
		"helix.frontend-url=http://127.0.0.1:5173"
})
@AutoConfigureMockMvc
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void loginRedirectsToGitlabAuthorize() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/gitlab")).andExpect(status().isFound()).andReturn();
		URI location = URI.create(result.getResponse().getRedirectedUrl());
		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo("hackers.oalite.com");
		assertThat(location.getPath()).isEqualTo("/oauth/authorize");
		String query = location.getRawQuery();
		assertThat(query).contains("client_id=test-client");
		assertThat(query).contains("response_type=code");
		assertThat(query).contains("scope=read_user");
		assertThat(query).contains("redirect_uri=");
		assertThat(query).contains("state=");
		assertThat(query).doesNotContain("client_secret");
	}

	@Test
	void callbackWithoutStateIsDenied() throws Exception {
		mockMvc.perform(get("/api/auth/gitlab/callback").param("code", "abc").param("state", "nope"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("http://127.0.0.1:5173/login?auth_error=denied"));
		mockMvc.perform(get("/api/auth/callback").param("code", "abc").param("state", "nope"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("http://127.0.0.1:5173/login?auth_error=denied"));
	}

	@Test
	void meReturnsTheSessionUserWithoutTheToken() throws Exception {
		mockMvc.perform(get("/api/auth/me").sessionAttr(AuthController.SESSION_USER, new GitlabUser(7, "ada", "Ada", null))
				.sessionAttr(AuthController.SESSION_TOKEN, "secret-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.username").value("ada"))
				.andExpect(jsonPath("$.name").value("Ada"))
				.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	void logoutInvalidatesTheSession() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AuthController.SESSION_USER, new GitlabUser(7, "ada", "Ada", null));
		session.setAttribute(AuthController.SESSION_TOKEN, "secret-token");

		mockMvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());

		assertThat(session.isInvalid()).isTrue();
	}

}
