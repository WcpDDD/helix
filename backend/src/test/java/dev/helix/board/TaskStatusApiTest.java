package dev.helix.board;

import java.util.Map;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabSessionCredential;
import dev.helix.gitlab.GitlabIssueClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskStatusApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IssueRepository issues;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private TaskAligner aligner;

	@Autowired
	private GitlabSessionCredential credential;

	@MockitoBean
	private GitlabIssueClient gitlab;

	@AfterEach
	void restorePracticeTask() {
		credential.forget("tok");
		transactions.executeWithoutResult(ignored -> issues.findByGitIssueNumber(21).ifPresent(issue -> {
			issue.adoptStatus("实现中", "实现");
			issue.getTask().adopt("实现中", "实现");
		}));
	}

	@Test
	void transitionRequiresTheGitlabSession() throws Exception {
		mockMvc.perform(post("/api/tasks/21/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"审阅中\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void transitionWritesTheMainIssueThenTheDatabase() throws Exception {
		when(gitlab.description(anyString(), anyString(), anyString(), anyString(), eq(21))).thenReturn("开头正文");

		mockMvc.perform(post("/api/tasks/21/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"审阅中\"}")
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("审阅中"))
				.andExpect(jsonPath("$.stage").value("实现"));

		verify(gitlab).writeDescription(eq("tok"), eq("hackers.oalite.com"), eq("qingflow-develop"),
				eq("qingflow-workspace"), eq(21), org.mockito.ArgumentMatchers.contains("状态：审阅中"), eq(false));
		mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '21')].status", contains("审阅中")));
	}

	@Test
	void failedWriteAlignsTheDatabaseToTheIssue() throws Exception {
		when(gitlab.description(anyString(), anyString(), anyString(), anyString(), eq(21)))
				.thenReturn("<!-- helix -->\n状态：实现中\n阶段：实现\n<!-- /helix -->");
		doThrow(new GitlabIssueException(502)).when(gitlab)
				.writeDescription(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());

		mockMvc.perform(post("/api/tasks/21/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"审阅中\"}")
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value("实现中"));

		mockMvc.perform(get("/api/tasks")).andExpect(jsonPath("$[?(@.code == '21')].status", contains("实现中")));
	}

	@Test
	void listingDoesNotCallGitlab() throws Exception {
		mockMvc.perform(get("/api/tasks").sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '21')].status", contains("实现中")));

		verify(gitlab, never()).descriptions(anyString(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void governanceTickAdoptsTheMainIssueStatus() throws Exception {
		when(gitlab.descriptions(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(
				Map.of(21, "<!-- helix -->\n状态：可上线\n阶段：上线\n<!-- /helix -->"));
		credential.remember("tok");

		aligner.tick();

		mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '21')].status", contains("可上线")))
				.andExpect(jsonPath("$[?(@.code == '21')].stage", contains("上线")));
		credential.forget("tok");
	}

}
