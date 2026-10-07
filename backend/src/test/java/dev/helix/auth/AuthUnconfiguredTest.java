package dev.helix.auth;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"helix.gitlab.client-id=",
		"helix.gitlab.client-secret=",
		"helix.frontend-url=http://127.0.0.1:5173"
})
@AutoConfigureMockMvc
class AuthUnconfiguredTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void meIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void loginReturnsHome() throws Exception {
		mockMvc.perform(get("/api/auth/gitlab"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("http://127.0.0.1:5173/login?auth_error=not_configured"));
	}

}
