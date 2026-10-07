package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IssueBodyApiTest {

	private static final String PROSE = "GITLAB-BODY-SENTINEL 开头";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GitlabIssueClient gitlabIssues;

	@Test
	void bodyRequiresTheGitlabSession() throws Exception {
		mockMvc.perform(get("/api/issues/21/body")).andExpect(status().isUnauthorized());
	}

	@Test
	void bodyIsReadFromGitlabAndStripped() throws Exception {
		when(gitlabIssues.description(eq("secret-token"), eq("hackers.oalite.com"), eq("qingflow-develop"),
				eq("qingflow-workspace"), eq(21))).thenReturn(PROSE + "\n<!-- helix -->\n状态：实现中\n<!-- /helix -->");

		mockMvc.perform(get("/api/issues/21/body").sessionAttr(AuthController.SESSION_TOKEN, "secret-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body").value(PROSE));

		mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PROSE))));
	}

	@Test
	void unknownIssueIsNotFound() throws Exception {
		mockMvc.perform(get("/api/issues/999999/body").sessionAttr(AuthController.SESSION_TOKEN, "secret-token"))
				.andExpect(status().isNotFound());
	}

	@Test
	void notesAreReadFromGitlabAndNotStored() throws Exception {
		when(gitlabIssues.comments(eq("secret-token"), eq("hackers.oalite.com"), eq("qingflow-develop"),
				eq("qingflow-workspace"), eq(21)))
				.thenReturn(List.of(new GitlabComment(9, "Ada", "2026-08-06T01:02:03Z", "GITLAB-NOTE-SENTINEL")));

		mockMvc.perform(get("/api/issues/21/notes").sessionAttr(AuthController.SESSION_TOKEN, "secret-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.notes[0].id").value(9))
				.andExpect(jsonPath("$.notes[0].author").value("Ada"))
				.andExpect(jsonPath("$.notes[0].body").value("GITLAB-NOTE-SENTINEL"));

		mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("GITLAB-NOTE-SENTINEL"))));
	}

	@Test
	void notesRequireTheGitlabSession() throws Exception {
		mockMvc.perform(get("/api/issues/21/notes")).andExpect(status().isUnauthorized());
	}

	@Test
	void gitlabDenialBecomesUnauthorized() throws Exception {
		when(gitlabIssues.description(eq("secret-token"), eq("hackers.oalite.com"), eq("qingflow-develop"),
				eq("qingflow-workspace"), eq(21))).thenThrow(new GitlabIssueException(401));

		mockMvc.perform(get("/api/issues/21/body").sessionAttr(AuthController.SESSION_TOKEN, "secret-token"))
				.andExpect(status().isUnauthorized());
	}

}
