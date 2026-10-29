package dev.helix.agentkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.ProjectRepository;
import dev.helix.work.ProjectWorkspaces;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"helix.work-root=./target/helix-home-work",
		"helix.agentkit.workspace=./target/helix-home-agents",
		"helix.agentkit.model=",
		"helix.agentkit.api-key=",
		"helix.agentkit.vision-model="
})
class HomeAgentApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private ProjectWorkspaces workspaces;

	@Test
	void homeAgentUsesTheClonedProjectDirectory() throws Exception {
		var project = projects.findAll(Sort.by("id")).getFirst();
		Path clone = workspaces.directory(project);
		if (!clone.toString().contains("helix-home-work")) {
			throw new AssertionError(clone.toString());
		}
		delete(clone);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AuthController.SESSION_USER, new GitlabUser(1, "chenxinpei", "chenxinpei", null));

		String first = mockMvc.perform(get("/api/home/agent").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.preparing").value(true))
				.andExpect(jsonPath("$.workspace").value(""))
				.andExpect(jsonPath("$.userId").value(1))
				.andExpect(jsonPath("$.username").value("chenxinpei"))
				.andExpect(jsonPath("$.sessionId").isNotEmpty())
				.andExpect(jsonPath("$.sessions").isArray())
				.andReturn().getResponse().getContentAsString();
		mockMvc.perform(get("/api/home/agent/stream").session(session))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/event-stream"))
				.andExpect(content().string(containsString("event: done")));
		String second = mockMvc.perform(post("/api/home/agent/session").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.messages").isEmpty())
				.andExpect(jsonPath("$.sessionId").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.sessionId");
		String secondId = com.jayway.jsonpath.JsonPath.read(second, "$.sessionId");
		org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId);
		MockHttpSession other = new MockHttpSession();
		other.setAttribute(AuthController.SESSION_USER, new GitlabUser(2, "other", "other", null));
		mockMvc.perform(get("/api/home/agent").session(other))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(2))
				.andExpect(jsonPath("$.username").value("other"))
				.andExpect(jsonPath("$.messages").isEmpty());
		mockMvc.perform(post("/api/home/agent").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"看一下仓库\"}")
				.session(session))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value("主分支还在准备。"));

		Files.createDirectories(clone);
		Files.createDirectories(clone.resolve(".git"));
		mockMvc.perform(get("/api/home/agent").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.preparing").value(false))
				.andExpect(jsonPath("$.workspace").value(clone.toAbsolutePath().normalize().toString()));
		mockMvc.perform(post("/api/home/agent").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"看一下仓库\"}")
				.session(session))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value("AGENTKIT_MODEL 和 AGENTKIT_API_KEY 还没配。"));
	}

	private static void delete(Path root) throws Exception {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

}
