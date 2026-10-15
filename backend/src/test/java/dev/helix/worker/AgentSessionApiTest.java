package dev.helix.worker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import dev.helix.board.IssueRepository;
import dev.helix.board.ProjectRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentSessionApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IssueRepository issues;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private WorkerRepository workers;

	@Autowired
	private SubtaskRouteRepository routes;

	@Autowired
	private AgentSessionRepository sessions;

	@AfterEach
	void cleanup() {
		issues.findByGitIssueNumber(22).ifPresent(issue -> routes.deleteById(issue.getId()));
		workers.deleteById("worker-session");
		sessions.findAll().stream()
				.filter(item -> item.getIssueNumber() == 880010 || item.getIssueNumber() == 880011
						|| item.getIssueNumber() == 880012)
				.forEach(sessions::delete);
		workers.deleteById("worker-idle");
		workers.deleteById("worker-busy");
		workers.deleteById("worker-fail");
	}

	@Test
	void missingSessionStaysUnassigned() throws Exception {
		mockMvc.perform(get("/api/issues/999999/agent-session"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assigned").value(false));
	}

	@Test
	void childIssueSessionFollowsTheBoundWorker() throws Exception {
		var issue = issues.findByGitIssueNumber(22).orElseThrow();
		workers.save(new WorkerEntity("worker-session", "会话机", Instant.EPOCH));
		routes.save(new SubtaskRouteEntity(issue.getId(), "worker-session", 1L, "系统", "去做这件事"));

		mockMvc.perform(get("/api/issues/22/agent-session"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assigned").value(true))
				.andExpect(jsonPath("$.alive").value(false))
				.andExpect(jsonPath("$.workerName").value("会话机"))
				.andExpect(jsonPath("$.message").value("去做这件事"));

		workers.save(new WorkerEntity("worker-session", "会话机", Instant.now()));
		mockMvc.perform(get("/api/issues/22/agent-session"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.alive").value(true));
	}

	@Test
	void createFailsWhenNoWorkerCanBeCalled() throws Exception {
		retireWorkers();

		mockMvc.perform(post("/api/issues/880012/agent-session"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value("没有可用的 Worker。"));
		mockMvc.perform(get("/api/issues/880012/agent-session"))
				.andExpect(jsonPath("$.assigned").value(false));
	}

	@Test
	void createUsesTheWorkerWithTheFewestSessions() throws Exception {
		retireWorkers();
		AtomicInteger idleHits = new AtomicInteger();
		AtomicInteger busyHits = new AtomicInteger();
		AtomicReference<String> idleBody = new AtomicReference<>();
		HttpServer idle = server(200, idleHits, idleBody);
		HttpServer busy = server(200, busyHits, new AtomicReference<>());
		idle.start();
		busy.start();
		try {
			heartbeat("worker-idle", "空闲机", idle.getAddress().getPort());
			heartbeat("worker-busy", "忙碌机", busy.getAddress().getPort());
			long projectId = projects.findAll(Sort.by("id")).getFirst().getId();
			sessions.save(new AgentSessionEntity(projectId, 880010, 0, "worker-busy"));

			mockMvc.perform(post("/api/issues/880011/agent-session"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.assigned").value(true))
					.andExpect(jsonPath("$.alive").value(true))
					.andExpect(jsonPath("$.workerId").value("worker-idle"))
					.andExpect(jsonPath("$.workerName").value("空闲机"));
			assertThat(idleHits).hasValue(1);
			assertThat(idleBody.get()).contains("\"issueNumber\":880011");
			assertThat(busyHits).hasValue(0);

			mockMvc.perform(get("/api/issues/880011/agent-session"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.workerId").value("worker-idle"));
		}
		finally {
			idle.stop(0);
			busy.stop(0);
		}
	}

	@Test
	void createFailsWhenTheWorkerRejectsTheSession() throws Exception {
		retireWorkers();
		HttpServer server = server(500, new AtomicInteger(), new AtomicReference<>());
		server.start();
		try {
			heartbeat("worker-fail", "失败机", server.getAddress().getPort());
			mockMvc.perform(post("/api/issues/880012/agent-session"))
					.andExpect(status().isBadGateway())
					.andExpect(jsonPath("$.detail").value("创建 Agent 会话失败。"));
			mockMvc.perform(get("/api/issues/880012/agent-session"))
					.andExpect(jsonPath("$.assigned").value(false));
		}
		finally {
			server.stop(0);
		}
	}

	private void retireWorkers() {
		for (WorkerEntity worker : workers.findAll()) {
			worker.touch(Instant.EPOCH);
			workers.save(worker);
		}
	}

	private void heartbeat(String id, String name, int port) throws Exception {
		mockMvc.perform(post("/api/workers/heartbeat")
						.contentType("application/json")
						.content("{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"address\":\"http://127.0.0.1:" + port + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.alive").value(true));
	}

	private static HttpServer server(int status, AtomicInteger hits, AtomicReference<String> body) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/sessions", exchange -> {
			hits.incrementAndGet();
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] payload = "{\"id\":\"s\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, status >= 400 ? -1 : payload.length);
			if (status < 400) {
				exchange.getResponseBody().write(payload);
			}
			exchange.close();
		});
		return server;
	}

}
