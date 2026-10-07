package dev.helix.board;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BoardGroupingTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper json;

	@MockitoBean
	private WorkspaceBoard boards;

	@Test
	void groupsFollowTheWorkspaceFile() throws Exception {
		when(boards.current(anyLong())).thenReturn(BoardFile.parse("""
				version: 1
				tasks:
				  - main: 21
				    children:
				      - 22
				"""));

		JsonNode tasks = json.readTree(mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());

		JsonNode practice = find(tasks, "21");
		assertThat(practice.get("issues")).hasSize(1);
		assertThat(practice.get("issues").get(0).get("id").asText()).isEqualTo("22");
		JsonNode detached = find(tasks, "36");
		assertThat(detached.get("issues")).isEmpty();
	}

	private static JsonNode find(JsonNode tasks, String code) {
		for (JsonNode task : tasks) {
			if (code.equals(task.get("code").asText())) {
				return task;
			}
		}
		throw new AssertionError("missing task " + code);
	}

}
