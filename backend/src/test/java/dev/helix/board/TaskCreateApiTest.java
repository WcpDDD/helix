package dev.helix.board;

import dev.helix.auth.AuthController;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.CreatedIssue;
import dev.helix.gitlab.GitlabIssueClient.UploadedFile;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskCreateApiTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GitlabIssueClient gitlab;

	@MockitoBean
	private ProposalHandoff proposals;

	@Test
	void delegationHandsTheIssueToProposal() throws Exception {
		when(gitlab.createIssue(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(new CreatedIssue(321, "https://hackers.oalite.com/qingflow-develop/qingflow-workspace/-/issues/321"));

		String body = """
				{"title":"创建任务","kind":"bug","delegate":true,"fields":{
				"复现步骤":"点保存","期望行为":"出现 issue","实际行为":"没有","环境":"本地","影响面":"任务列表"}}
				""";

		mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body)
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("321"));

		verify(gitlab).createIssue(eq("tok"), eq("hackers.oalite.com"), eq("qingflow-develop"), eq("qingflow-workspace"),
				eq("创建任务"), org.mockito.ArgumentMatchers.contains("点保存"),
				eq(java.util.List.of("status/proposal", "type/bug")));
		verify(proposals).ensure(org.mockito.ArgumentMatchers.any());
		verify(proposals).hand(org.mockito.ArgumentMatchers.any(), eq(321));
	}

	@Test
	void plainSaveDoesNotAddTheAiLabel() throws Exception {
		when(gitlab.createIssue(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(new CreatedIssue(322, "https://hackers.oalite.com/x/-/issues/322"));

		String body = """
				{"title":"只保存","kind":"bug","fields":{
				"复现步骤":"点保存","期望行为":"出现 issue","实际行为":"没有","环境":"本地","影响面":"任务列表"}}
				""";

		mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body)
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isCreated());

		verify(gitlab).createIssue(anyString(), anyString(), anyString(), anyString(), eq("只保存"), anyString(),
				eq(java.util.List.of("status/proposal", "type/bug")));
		verify(proposals, org.mockito.Mockito.never()).hand(any(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	void attachmentMarkdownIsWrittenIntoTheIssue() throws Exception {
		when(gitlab.createIssue(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(new CreatedIssue(323, "https://hackers.oalite.com/x/-/issues/323"));

		String body = """
				{"title":"带附件","kind":"bug","attachments":["![草图](/uploads/a/sketch.png)"],"fields":{
				"复现步骤":"点保存","期望行为":"出现 issue","实际行为":"没有","环境":"本地","影响面":"任务列表"}}
				""";

		mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body)
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isCreated());

		verify(gitlab).createIssue(anyString(), anyString(), anyString(), anyString(), eq("带附件"),
				org.mockito.ArgumentMatchers.contains("![草图](/uploads/a/sketch.png)"),
				eq(java.util.List.of("status/proposal", "type/bug")));
	}

	@Test
	void uploadSendsTheFileToGitlab() throws Exception {
		when(gitlab.upload(anyString(), anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
				.thenReturn(new UploadedFile("草图.png", "![草图.png](/uploads/a/sketch.png)"));

		mockMvc.perform(multipart("/api/tasks/uploads")
				.file(new org.springframework.mock.web.MockMultipartFile("file", "notes/草图.png", "image/png",
						new byte[] { 1, 2, 3 }))
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.markdown").value("![草图.png](/uploads/a/sketch.png)"));

		verify(gitlab).upload(eq("tok"), eq("hackers.oalite.com"), eq("qingflow-develop"), eq("qingflow-workspace"),
				eq("草图.png"), org.mockito.ArgumentMatchers.argThat(value -> java.util.Arrays.equals(value, new byte[] { 1, 2, 3 })));
	}

	@Test
	void missingFieldIsRejected() throws Exception {
		mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"创建任务\",\"kind\":\"tech\",\"fields\":{\"背景\":\"升级\"}}")
				.sessionAttr(AuthController.SESSION_TOKEN, "tok"))
				.andExpect(status().isBadRequest());
	}

}
