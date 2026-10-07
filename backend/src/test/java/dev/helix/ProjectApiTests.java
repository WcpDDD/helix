package dev.helix;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.ProjectRepository;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.GitlabProjectClient;
import dev.helix.gitlab.GitlabProjectRef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projects;

	@MockitoBean
	private GitlabProjectClient gitlab;

	@Test
	void healthReportsOk() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.service").value("helix"));
	}

	@Test
	void projectsExposeWorkspace() throws Exception {
		mockMvc.perform(get("/api/projects"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("qingflow-workspace"))
				.andExpect(jsonPath("$[0].workspace.host").value("hackers.oalite.com"))
				.andExpect(jsonPath("$[0].workspace.path").value("qingflow-develop/qingflow-workspace"));
	}

	@AfterEach
	void restoreName() {
		projects.findAll(Sort.by("id")).stream()
				.filter(project -> "probe".equals(project.getGitOwner()) && "empty".equals(project.getGitRepo()))
				.forEach(projects::delete);
		projects.findAll(Sort.by("id")).stream().findFirst().ifPresent(project -> {
			if (!"qingflow-workspace".equals(project.getName())) {
				project.place("qingflow-workspace", project.getGitHost(), project.getGitOwner(), project.getGitRepo());
				projects.save(project);
			}
		});
	}

	@Test
	void createsAnotherProjectAndSwitchesTheBoard() throws Exception {
		when(gitlab.find(anyString(), anyString(), eq("probe/empty")))
				.thenReturn(new GitlabProjectRef("probe/empty", "探针", "https://hackers.oalite.com/probe/empty"));
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AuthController.SESSION_USER, user());
		session.setAttribute(AuthController.SESSION_TOKEN, "tok");
		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"探针项目\",\"path\":\"probe/empty\"}").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("探针项目"))
				.andExpect(jsonPath("$.workspace.path").value("probe/empty"));
		mockMvc.perform(get("/api/tasks").session(session)).andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/api/projects/current").session(session))
				.andExpect(jsonPath("$.workspace.path").value("probe/empty"));
		mockMvc.perform(get("/api/tasks")).andExpect(jsonPath("$[0].code").exists());
	}

	@Test
	void saveRequiresTheGitlabSession() throws Exception {
		mockMvc.perform(put("/api/projects/1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"qingflow-workspace\",\"path\":\"qingflow-develop/qingflow-workspace\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void saveRefusesToMoveABoardThatAlreadyHasIssues() throws Exception {
		when(gitlab.find(anyString(), anyString(), eq("other/repo")))
				.thenReturn(new GitlabProjectRef("other/repo", "其他", "https://hackers.oalite.com/other/repo"));
		long id = projects.findAll(Sort.by("id")).getFirst().getId();
		mockMvc.perform(put("/api/projects/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"qingflow-workspace\",\"path\":\"other/repo\"}")
				.sessionAttr(AuthController.SESSION_USER, user()).sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isConflict());
		mockMvc.perform(get("/api/projects"))
				.andExpect(jsonPath("$[0].workspace.path").value("qingflow-develop/qingflow-workspace"));
	}

	@Test
	void saveKeepsTheCurrentWorkspace() throws Exception {
		when(gitlab.find(anyString(), anyString(), eq("qingflow-develop/qingflow-workspace"))).thenReturn(
				new GitlabProjectRef("qingflow-develop/qingflow-workspace", "qingflow-workspace",
						"https://hackers.oalite.com/qingflow-develop/qingflow-workspace"));
		long id = projects.findAll(Sort.by("id")).getFirst().getId();
		mockMvc.perform(put("/api/projects/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"轻流\",\"path\":\"https://hackers.oalite.com/qingflow-develop/qingflow-workspace\"}")
				.sessionAttr(AuthController.SESSION_USER, user()).sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("轻流"))
				.andExpect(jsonPath("$.workspace.path").value("qingflow-develop/qingflow-workspace"));
	}

	@Test
	void missingGitlabProjectIsNotFound() throws Exception {
		when(gitlab.find(anyString(), anyString(), eq("missing/repo"))).thenThrow(new GitlabIssueException(404));
		long id = projects.findAll(Sort.by("id")).getFirst().getId();
		mockMvc.perform(put("/api/projects/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"qingflow-workspace\",\"path\":\"missing/repo\"}")
				.sessionAttr(AuthController.SESSION_USER, user()).sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isNotFound());
	}

	private static GitlabUser user() {
		return new GitlabUser(1, "chenxinpei", "chenxinpei", null);
	}

}
