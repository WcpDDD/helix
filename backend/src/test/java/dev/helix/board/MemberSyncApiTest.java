package dev.helix.board;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.gitlab.GitlabDirectory;
import dev.helix.gitlab.GitlabDirectoryClient;
import dev.helix.gitlab.GitlabIssueException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MemberSyncApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository members;

	@Autowired
	private MemberTeamRepository teams;

	@Autowired
	private MemberLabelRepository labels;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private TransactionTemplate transactions;

	@MockitoBean
	private GitlabDirectoryClient gitlab;

	@AfterEach
	void removeProbe() {
		transactions.executeWithoutResult(ignored -> {
			projects.findAll().stream().findFirst().ifPresent(project -> teams.deleteForProject(project.getId()));
			for (String login : List.of("sync-kept", "sync-left", "sync-new")) {
				members.findByGitLogin(login).ifPresent(members::delete);
			}
			labels.findByName("设计").ifPresent(labels::delete);
		});
	}

	@Test
	void syncRequiresTheGitlabSession() throws Exception {
		mockMvc.perform(post("/api/members/sync")).andExpect(status().isUnauthorized());
	}

	@Test
	void syncKeepsHelixRolesAndLabels() throws Exception {
		assign("sync-kept", "旧名", "[\"前端\"]");
		assign("sync-left", "离开的人", "[\"架构师\"]");
		mockMvc.perform(post("/api/members/labels").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"设计\"}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isOk());
		when(gitlab.load(anyString(), anyString(), anyString(), anyString())).thenReturn(directory());

		mockMvc.perform(post("/api/members/sync").sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.labels", hasItem("设计")))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-kept')].name", contains("新名")))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-kept')].teamIds", hasItem(contains("backend"))))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-kept')].roles", hasItem(contains("前端"))))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-left')].roles", hasItem(contains("架构师"))))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-new')].name", contains("新人")));
	}

	@Test
	void failedSyncLeavesHelixConfiguration() throws Exception {
		assign("sync-kept", "旧名", "[\"产品\"]");
		when(gitlab.load(anyString(), anyString(), anyString(), anyString())).thenThrow(new GitlabIssueException(502));

		mockMvc.perform(post("/api/members/sync").sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isBadGateway());

		mockMvc.perform(get("/api/members"))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-kept')].name", contains("旧名")))
				.andExpect(jsonPath("$.members[?(@.login == 'sync-kept')].roles", hasItem(contains("产品"))));
	}

	private void assign(String login, String name, String roles) throws Exception {
		mockMvc.perform(put("/api/members/" + login + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"roles\":" + roles + "}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isOk());
	}

	private static GitlabDirectory directory() {
		return new GitlabDirectory(
				List.of(new GitlabDirectory.Person("sync-kept", "新名", List.of("backend")),
						new GitlabDirectory.Person("sync-new", "新人", List.of("backend"))),
				List.of(new GitlabDirectory.Team("backend", "backend")));
	}

	private static GitlabUser user() {
		return new GitlabUser(1, "chenxinpei", "chenxinpei", null);
	}

}
