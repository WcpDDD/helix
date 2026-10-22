package dev.helix.auth;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StoredLoginTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LoginStore logins;

	@Autowired
	private GitlabLoginRepository repository;

	@MockitoBean
	private GitlabOAuthClient oauth;

	@AfterEach
	void clean() {
		repository.deleteAll();
	}

	@Test
	void cookieRestoresTheUserWhenTheSessionIsGone() throws Exception {
		String id = logins.save(login("secret-token", "refresh-token", System.currentTimeMillis() + 3_600_000));

		mockMvc.perform(get("/api/auth/me").cookie(new Cookie(LoginCookie.NAME, id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("ada"))
				.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	void logoutForgetsTheStoredLogin() throws Exception {
		String id = logins.save(login("secret-token", "refresh-token", System.currentTimeMillis() + 3_600_000));

		mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(LoginCookie.NAME, id)))
				.andExpect(status().isNoContent());

		assertThat(repository.findById(id)).isEmpty();
		mockMvc.perform(get("/api/auth/me").cookie(new Cookie(LoginCookie.NAME, id)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredLoginIsRefreshedFromTheStore() throws Exception {
		when(oauth.refresh("old-refresh")).thenReturn(
				new GitlabLogin(null, "new-access", "new-refresh", System.currentTimeMillis() + 3_600_000));
		String id = logins.save(login("old-access", "old-refresh", System.currentTimeMillis() - 1_000));

		mockMvc.perform(get("/api/auth/me").cookie(new Cookie(LoginCookie.NAME, id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("ada"));

		GitlabLoginEntity row = repository.findById(id).orElseThrow();
		assertThat(row.getAccessToken()).isEqualTo("new-access");
		assertThat(row.getRefreshToken()).isEqualTo("new-refresh");
	}

	private static GitlabLogin login(String accessToken, String refreshToken, long expiresAt) {
		return new GitlabLogin(new GitlabUser(7, "ada", "Ada", null), accessToken, refreshToken, expiresAt);
	}

}
