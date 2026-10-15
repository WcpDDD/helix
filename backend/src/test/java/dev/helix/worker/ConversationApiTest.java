package dev.helix.worker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

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
class ConversationApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private WorkerRepository workers;

	@Autowired
	private AgentSessionRepository sessions;

	@AfterEach
	void cleanup() {
		long projectId = projects.findAll(Sort.by("id")).getFirst().getId();
		sessions.findByProjectIdAndIssueNumber(projectId, 880021).ifPresent(sessions::delete);
		workers.deleteById("worker-talk");
		workers.deleteById("worker-quiet");
	}

	@Test
	void missingSessionCannotReadTheConversation() throws Exception {
		mockMvc.perform(get("/api/issues/880099/conversations"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("这张 issue 还没有 Agent 会话。"));
	}

	@Test
	void deadWorkerStopsTheConversation() throws Exception {
		long projectId = projects.findAll(Sort.by("id")).getFirst().getId();
		workers.save(new WorkerEntity("worker-quiet", "停机", Instant.EPOCH));
		sessions.save(new AgentSessionEntity(projectId, 880021, 0, "worker-quiet"));

		mockMvc.perform(get("/api/issues/880021/conversations"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value("绑定的 Worker 不存活，会话已中断。"));
	}

	@Test
	void messagesGoToTheBoundWorker() throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String query = exchange.getRequestURI().getRawQuery();
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
					+ (query == null ? "" : "?" + query) + " " + body);
			byte[] payload = "{\"messages\":[],\"hasMore\":false}".getBytes(StandardCharsets.UTF_8);
			if ("POST".equals(exchange.getRequestMethod())) {
				payload = "{\"user\":{\"seq\":1},\"assistant\":{\"seq\":2}}".getBytes(StandardCharsets.UTF_8);
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
		});
		server.start();
		try {
			mockMvc.perform(post("/api/workers/heartbeat")
					.contentType("application/json")
					.content("{\"id\":\"worker-talk\",\"name\":\"会话机\",\"address\":\"http://127.0.0.1:"
							+ server.getAddress().getPort() + "\"}"))
					.andExpect(status().isOk());
			long projectId = projects.findAll(Sort.by("id")).getFirst().getId();
			sessions.save(new AgentSessionEntity(projectId, 880021, 0, "worker-talk"));

			mockMvc.perform(get("/api/issues/880021/conversations").param("before", "8"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.hasMore").value(false));
			assertThat(seen.get()).isEqualTo("GET /sessions/880021/conversations?before=8 ");

			mockMvc.perform(post("/api/issues/880021/messages")
					.contentType("application/json")
					.content("{\"text\":\"接着看\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.assistant.seq").value(2));
			assertThat(seen.get()).contains("POST /sessions/880021/messages").contains("接着看");
		}
		finally {
			server.stop(0);
		}
	}

}
