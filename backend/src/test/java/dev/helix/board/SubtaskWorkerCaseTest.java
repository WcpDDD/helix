package dev.helix.board;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.helix.agentkit.AgentKitProperties;
import dev.helix.worker.HelixWorker;
import dev.helix.worker.SubtaskRouteEntity;
import dev.helix.worker.SubtaskRouteRepository;
import dev.helix.worker.WorkerEntity;
import dev.helix.worker.WorkerRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SubtaskWorkerCaseTest {

	private static final Path DATABASE = Path.of(System.getProperty("java.io.tmpdir"),
			"helix-worker-case-" + ProcessHandle.current().pid() + ".db");

	private static final AtomicInteger ISSUE_NUMBERS = new AtomicInteger(900_000);

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private MemberRepository members;

	@Autowired
	private TaskRepository tasks;

	@Autowired
	private IssueRepository issues;

	@Autowired
	private AgentRepository agents;

	@Autowired
	private WorkerRepository workers;

	@Autowired
	private SubtaskRouteRepository routes;

	@Test
	void deadWorkerInterruptsTheChildAndWaits() throws Exception {
		Fixture fixture = seed("wait");
		heartbeat("worker-wait");

		mockMvc.perform(post("/api/subtasks/" + fixture.childId + "/schedule")
						.contentType("application/json")
						.content(schedule(fixture.agentId, "先占住这个 worker。")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("pending"))
				.andExpect(jsonPath("$.workerId").value("worker-wait"));

		WorkerEntity worker = workers.findById("worker-wait").orElseThrow();
		worker.touch(Instant.EPOCH);
		workers.save(worker);

		mockMvc.perform(post("/api/subtasks/" + fixture.childId + "/schedule")
						.contentType("application/json")
						.content(schedule(fixture.agentId, "worker 已经没了。")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.state").value("interrupted"))
				.andExpect(jsonPath("$.workerId").value("worker-wait"));

		heartbeat("worker-wait");

		mockMvc.perform(post("/api/subtasks/" + fixture.childId + "/schedule")
						.contentType("application/json")
						.content(schedule(fixture.agentId, "worker 回来了。")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("pending"))
				.andExpect(jsonPath("$.workerId").value("worker-wait"));
	}

	@Test
	@Timeout(180)
	void workerRunsTheChildOnItsOwnDirectory() throws Exception {
		for (WorkerEntity worker : workers.findAll()) {
			worker.touch(Instant.EPOCH);
			workers.save(worker);
		}
		Fixture fixture = seed("run");
		Path root = Files.createTempDirectory("helix-worker-root");
		HelixWorker worker = new HelixWorker(URI.create("http://127.0.0.1:" + port), "worker-run", "run", root,
				labProperties());
		worker.heartbeat();

		mockMvc.perform(post("/api/subtasks/" + fixture.childId + "/schedule")
						.contentType("application/json")
						.content(schedule(fixture.agentId,
								"Create a file named hello.txt whose entire content is pong. Then reply with the single word pong.")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("pending"))
				.andExpect(jsonPath("$.workerId").value("worker-run"));

		assertThat(worker.pollAndRun()).isEqualTo(1);

		SubtaskRouteEntity route = routes.findById(fixture.childId).orElseThrow();
		Path workspace = root.resolve("subtask-" + fixture.childId).toAbsolutePath().normalize();
		assertThat(route.getWorkerId()).isEqualTo("worker-run");
		assertThat(route.getRouteState()).isEqualTo("bound");
		assertThat(Path.of(route.getWorkspace())).isEqualTo(workspace);
		assertThat(route.getPhase()).withFailMessage(() -> route.getPhase() + " " + route.getFailure() + " " + route.getResultText())
				.isEqualTo("COMPLETED");
		assertThat(Files.readString(workspace.resolve("hello.txt")).trim()).isEqualTo("pong");
	}

	private void heartbeat(String id) throws Exception {
		mockMvc.perform(post("/api/workers/heartbeat")
						.contentType("application/json")
						.content("{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.alive").value(true));
	}

	private Fixture seed(String name) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		ProjectEntity project = projects.save(new ProjectEntity(name, "local.test", name, "repo-" + suffix));
		MemberEntity member = members.save(new MemberEntity(project, "Ada", "ada-" + suffix));
		TaskEntity task = tasks.save(new TaskEntity(project, "open", "build", LocalDate.of(2026, 10, 13), null, null));
		int mainNumber = ISSUE_NUMBERS.getAndIncrement();
		int childNumber = ISSUE_NUMBERS.getAndIncrement();
		issues.save(issue(project, task, true, mainNumber, "主任务 " + name, member));
		IssueEntity child = issues.save(issue(project, task, false, childNumber, "子任务 " + name, member));
		AgentEntity agent = agents.save(new AgentEntity(project, "probe-" + suffix,
				"You work only inside the open workspace. Do the user's file change, then stop."));
		return new Fixture(child.getId(), agent.getId());
	}

	private static IssueEntity issue(ProjectEntity project, TaskEntity task, boolean main, int number, String title,
			MemberEntity member) {
		return new IssueEntity(project, task, main, number, title, "task", "build", "open", member, member,
				".", ".", ".", ".", null, null, Instant.parse("2026-10-13T00:00:00Z"));
	}

	private static String schedule(long agentId, String message) {
		return "{\"agentId\":" + agentId + ",\"message\":" + json(message) + "}";
	}

	private static String json(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static AgentKitProperties labProperties() throws Exception {
		String model = null;
		String baseUrl = null;
		String apiKey = null;
		for (String line : Files.readAllLines(Path.of("/Users/chenxinpei/code/agent-kit/.env"))) {
			int split = line.indexOf('=');
			if (split < 1 || line.startsWith("#")) {
				continue;
			}
			String key = line.substring(0, split).trim();
			String value = line.substring(split + 1).trim();
			if ("AGENTKIT_MODEL".equals(key)) {
				model = value;
			}
			else if ("AGENTKIT_BASE_URL".equals(key)) {
				baseUrl = value;
			}
			else if ("AGENTKIT_API_KEY".equals(key)) {
				apiKey = value;
			}
		}
		if (model == null || model.isBlank() || apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("agent-kit lab .env 缺 AGENTKIT_MODEL 或 AGENTKIT_API_KEY");
		}
		return new AgentKitProperties(null, model, baseUrl, apiKey, 6, null);
	}

	private record Fixture(long childId, long agentId) {
	}

}
