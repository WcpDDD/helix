package dev.helix.board;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MemberRoleApiTest {

	private static final String LOGIN = "role-probe";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository members;

	@Autowired
	private MemberLabelRepository labels;

	@Autowired
	private TransactionTemplate transactions;

	@AfterEach
	void removeProbe() {
		transactions.executeWithoutResult(ignored -> {
			members.findByGitLogin(LOGIN).ifPresent(members::delete);
			labels.findByName("设计").ifPresent(labels::delete);
		});
	}

	@Test
	void assigningRolesRequiresASignedInUser() throws Exception {
		mockMvc.perform(put("/api/members/" + LOGIN + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"probe\",\"roles\":[\"前端\"]}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unknownRoleIsRejected() throws Exception {
		mockMvc.perform(put("/api/members/" + LOGIN + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"probe\",\"roles\":[\"设计\"]}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rolesAreStoredAndCanBeCleared() throws Exception {
		mockMvc.perform(put("/api/members/" + LOGIN + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"probe\",\"roles\":[\"架构师\",\"前端\"]}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.login").value(LOGIN))
				.andExpect(jsonPath("$.roles", contains("前端", "架构师")));

		mockMvc.perform(get("/api/members/roles"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.labels", contains("前端", "后端", "测试", "产品", "架构师")))
				.andExpect(jsonPath("$.members[?(@.login == '" + LOGIN + "')].login", contains(LOGIN)));

		mockMvc.perform(put("/api/members/" + LOGIN + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"probe\",\"roles\":[]}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles", hasSize(0)));

		mockMvc.perform(get("/api/members/roles"))
				.andExpect(jsonPath("$.members[?(@.login == '" + LOGIN + "')].login", empty()));
	}

	@Test
	void newLabelCanBeAddedAndAssigned() throws Exception {
		mockMvc.perform(post("/api/members/labels").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"设计\"}"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/members/labels").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"设计\"}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", contains("前端", "后端", "测试", "产品", "架构师", "设计")));

		mockMvc.perform(put("/api/members/" + LOGIN + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"probe\",\"roles\":[\"设计\"]}")
				.sessionAttr(AuthController.SESSION_USER, user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles", contains("设计")));
	}

	private static GitlabUser user() {
		return new GitlabUser(1, "chenxinpei", "chenxinpei", null);
	}

}
