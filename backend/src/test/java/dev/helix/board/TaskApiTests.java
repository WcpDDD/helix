package dev.helix.board;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskApiTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper json;

	@Test
	void boardListsQingflowIssuesFromSqlite() throws Exception {
		JsonNode tasks = json.readTree(mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());
		assertImported(tasks);
	}

	static void assertImported(JsonNode tasks) {
		assertThat(tasks).hasSize(105);
		assertThat(findOptional(tasks, "1042")).isNull();
		int previous = Integer.MAX_VALUE;
		for (JsonNode task : tasks) {
			int code = Integer.parseInt(task.get("code").asText());
			assertThat(code).isLessThan(previous);
			previous = code;
		}
		JsonNode workflow = find(tasks, "147");
		assertThat(workflow.get("status").asText()).isEqualTo("已关闭");
		assertThat(workflow.get("stage").asText()).isEqualTo("实现");
		assertThat(workflow.get("issues")).hasSize(34);
		assertThat(workflow.get("edges")).hasSize(7);
		assertThat(workflow.get("edges").get(0).get("from").asText()).isEqualTo("149");
		assertThat(workflow.get("edges").get(0).get("to").asText()).isEqualTo("150");
		assertThat(workflow.get("edges").get(0).get("needsContract").asBoolean()).isTrue();
		assertThat(workflow.get("edges").get(0).get("contractRef").asText())
				.isEqualTo("openspec/changes/workflow-spec-apply-destructive-preview/design.md");
		JsonNode practice = find(tasks, "21");
		assertThat(practice.get("owner").asText()).isEqualTo("chenxinpei");
		assertThat(practice.get("issue").get("url").asText())
				.isEqualTo("https://hackers.oalite.com/qingflow-develop/qingflow-workspace/-/issues/21");
		assertThat(practice.get("status").asText()).isEqualTo("实现中");
		assertThat(practice.get("issues")).hasSize(3);
		assertThat(practice.get("issues").get(0).get("id").asText()).isEqualTo("22");
		assertThat(practice.get("issues").get(1).get("id").asText()).isEqualTo("36");
		assertThat(practice.get("issues").get(2).get("id").asText()).isEqualTo("101");
		JsonNode cockpit = find(tasks, "191");
		assertThat(cockpit.get("issue").get("mergeRequests").get(0).get("iid").asText()).isEqualTo("20");
		assertThat(cockpit.get("issue").get("mergeRequests").get(0).get("state").asText()).isEqualTo("打开");
	}

	private static JsonNode find(JsonNode tasks, String code) {
		JsonNode task = findOptional(tasks, code);
		if (task == null) {
			throw new AssertionError("missing task " + code);
		}
		return task;
	}

	private static JsonNode findOptional(JsonNode tasks, String code) {
		for (JsonNode task : tasks) {
			if (code.equals(task.get("code").asText())) {
				return task;
			}
		}
		return null;
	}

}
