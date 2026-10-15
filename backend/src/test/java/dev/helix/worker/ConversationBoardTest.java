package dev.helix.worker;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.agentkit.api.execution.ExecutionChunk;
import io.agentkit.api.execution.ExecutionEvent;
import io.agentkit.api.execution.ExecutionPhase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationBoardTest {

	@TempDir
	Path temp;

	private final JsonMapper json = new JsonMapper();

	@Test
	void pagesTenMessagesAndKeepsStructuredParts() throws Exception {
		Path repo = repo();
		AtomicInteger starts = new AtomicInteger();
		ConversationBoard board = new ConversationBoard(temp.resolve("workers"), Map.of("HELIX_REPO", repo.toString()),
				(thread, message, workspace, env, sink) -> {
					starts.incrementAndGet();
					sink.onChunk(new ExecutionChunk(null, "先看一下", List.of(), null));
					sink.onEvent(new ExecutionEvent.ToolStarted(1, "call-1", "read", "{\"path\":\"README.md\"}"));
					sink.onEvent(new ExecutionEvent.ToolFinished(1, "call-1", "hello"));
					sink.onChunk(new ExecutionChunk("仓库里有说明", null, List.of(), null));
					sink.onEvent(new ExecutionEvent.RunEnded(ExecutionPhase.COMPLETED, "仓库里有说明", null, null));
				});

		WorkerSessions.Result first = board.send(21, "{\"text\":\"看看 README\",\"files\":[\"README.md\"]}");
		assertThat(first.status()).isEqualTo(200);
		for (int i = 0; i < 11; i++) {
			assertThat(board.send(21, "{\"text\":\"继续 " + i + "\"}").status()).isEqualTo(200);
		}

		JsonNode page = json.readTree(board.list(21, null).body());
		assertThat(page.path("messages")).hasSize(10);
		assertThat(page.path("hasMore").asBoolean()).isTrue();
		long oldest = page.path("messages").get(0).path("seq").asLong();
		JsonNode older = json.readTree(board.list(21, oldest).body());
		assertThat(older.path("messages")).hasSize(10);
		assertThat(older.path("messages").get(9).path("seq").asLong()).isLessThan(oldest);

		JsonNode sent = json.readTree(first.body());
		JsonNode assistant = json.readTree(board.content(21, sent.path("assistant").path("seq").asLong()).body());
		assertThat(assistant.path("status").asString()).isEqualTo("done");
		assertThat(assistant.toString()).contains("thinking").contains("先看一下");
		assertThat(assistant.toString()).contains("仓库里有说明");
		assertThat(assistant.toString()).contains("\"name\":\"read\"");
		assertThat(assistant.toString()).contains("\"path\":\"README.md\"");
		JsonNode user = json.readTree(board.content(21, sent.path("user").path("seq").asLong()).body());
		assertThat(user.toString()).contains("\"path\":\"README.md\"");

		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		board.writeStream(21, assistant.path("seq").asLong(), stream);
		assertThat(stream.toString(StandardCharsets.UTF_8)).contains("event: snapshot").contains("event: done");
		assertThat(starts.get()).isEqualTo(12);
	}

	@Test
	void rejectsASecondMessageWhileTheFirstIsRunning() throws Exception {
		ConversationBoard board = new ConversationBoard(temp.resolve("workers"), Map.of("HELIX_REPO", repo().toString()),
				(thread, message, workspace, env, sink) -> {
				});

		assertThat(board.send(21, "{\"text\":\"第一句\"}").status()).isEqualTo(200);
		WorkerSessions.Result blocked = board.send(21, "{\"text\":\"第二句\"}");

		assertThat(blocked.status()).isEqualTo(409);
		assertThat(blocked.body()).contains("上一轮还在进行");
	}

	@Test
	void attachmentStaysInsideTheWorkspace() throws Exception {
		Path repo = repo();
		ConversationBoard board = new ConversationBoard(temp.resolve("workers"), Map.of("HELIX_REPO", repo.toString()),
				(thread, message, workspace, env, sink) -> sink.onEvent(
						new ExecutionEvent.RunEnded(ExecutionPhase.COMPLETED, "好", null, null)));

		assertThat(board.send(21, "{\"text\":\"越界\",\"files\":[\"../secret\"]}").status()).isEqualTo(400);
		board.send(21, "{\"text\":\"打开说明\"}");
		JsonNode file = json.readTree(board.file(21, "README.md").body());

		assertThat(file.path("name").asString()).isEqualTo("README.md");
		assertThat(file.path("text").asString()).isEqualTo("hello");
		assertThat(board.file(21, "../secret").status()).isEqualTo(400);
	}

	@Test
	void interruptedTurnIsFailedAfterRestart() throws Exception {
		Path root = temp.resolve("workers");
		Map<String, String> env = Map.of("HELIX_REPO", repo().toString());
		new ConversationBoard(root, env, (thread, message, workspace, gitlab, sink) -> {
		}).send(21, "{\"text\":\"半截\"}");

		JsonNode page = json.readTree(new ConversationBoard(root, env, (thread, message, workspace, gitlab, sink) -> {
		}).list(21, null).body());

		assertThat(page.path("messages").get(1).path("status").asString()).isEqualTo("failed");
		assertThat(page.path("messages").get(1).path("preview").asString()).contains("worker 重启");
	}

	@Test
	void laterMessagesKeepTheSameThread() throws Exception {
		List<String> threads = new ArrayList<>();
		List<String> prompts = new ArrayList<>();
		Path root = temp.resolve("workers");
		Map<String, String> env = Map.of("HELIX_REPO", repo().toString());
		ConversationBoard.Runner runner = (thread, message, workspace, gitlab, sink) -> {
			threads.add(thread);
			prompts.add(message);
			sink.onEvent(new ExecutionEvent.RunEnded(ExecutionPhase.COMPLETED, "好", null, null));
		};
		ConversationBoard board = new ConversationBoard(root, env, runner);

		assertThat(board.send(21, "{\"text\":\"第一句\"}").status()).isEqualTo(200);
		assertThat(board.send(21, "{\"text\":\"第二句\"}").status()).isEqualTo(200);
		assertThat(board.send(22, "{\"text\":\"另一张\"}").status()).isEqualTo(200);

		assertThat(threads).hasSize(3);
		assertThat(threads.get(0)).isNotBlank().isEqualTo(threads.get(1));
		assertThat(threads.get(2)).isNotEqualTo(threads.get(0));
		assertThat(prompts.get(1)).isEqualTo("第二句");

		assertThat(new ConversationBoard(root, env, runner).send(21, "{\"text\":\"第三句\"}").status()).isEqualTo(200);
		assertThat(threads.get(3)).isEqualTo(threads.get(0));
		assertThat(prompts.get(3)).isEqualTo("第三句");
	}

	@Test
	void eachAgentKeepsItsOwnThread() throws Exception {
		List<String> threads = new ArrayList<>();
		Path root = temp.resolve("workers");
		Map<String, String> env = Map.of("HELIX_REPO", repo().toString());
		ConversationBoard board = new ConversationBoard(root, env, (thread, message, workspace, gitlab, sink) -> {
			threads.add(thread);
			sink.onEvent(new ExecutionEvent.RunEnded(ExecutionPhase.COMPLETED, message, null, null));
		});

		assertThat(board.send(21, 4, "{\"text\":\"给提案\"}").status()).isEqualTo(200);
		assertThat(board.send(21, 5, "{\"text\":\"给实现\"}").status()).isEqualTo(200);
		assertThat(board.send(21, 4, "{\"text\":\"提案再来\"}").status()).isEqualTo(200);

		assertThat(threads).hasSize(3);
		assertThat(threads.get(2)).isEqualTo(threads.get(0));
		assertThat(threads.get(1)).isNotEqualTo(threads.get(0));
		assertThat(json.readTree(board.list(21, 4, null).body()).path("messages")).hasSize(4);
		assertThat(json.readTree(board.list(21, 5, null).body()).path("messages")).hasSize(2);
	}

	@Test
	void gatewayListsAPage() throws Exception {
		WorkerSessions sessions = new WorkerSessions(temp.resolve("workers"), Map.of("HELIX_REPO", repo().toString()),
				(thread, message, workspace, env, sink) -> sink.onEvent(
						new ExecutionEvent.RunEnded(ExecutionPhase.COMPLETED, "好", null, null)));
		sessions.accept("{\"issueNumber\":21}");
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> WorkerGateway.handle(exchange, sessions));
		server.start();
		try {
			URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sessions/21/conversations");
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			JsonNode page = json.readTree(response.body());
			assertThat(page.path("messages")).hasSize(2);
			assertThat(page.path("messages").get(0).path("preview").asString()).contains("issue #21");
		}
		finally {
			server.stop(0);
		}
	}

	private Path repo() throws Exception {
		Path repo = temp.resolve("repo-" + System.nanoTime());
		Files.createDirectories(repo);
		git(repo, "init");
		git(repo, "config", "user.email", "worker@helix.test");
		git(repo, "config", "user.name", "worker");
		Files.writeString(repo.resolve("README.md"), "hello");
		git(repo, "add", "README.md");
		git(repo, "commit", "-m", "init");
		return repo;
	}

	private static void git(Path directory, String... args) throws Exception {
		String[] command = new String[args.length + 1];
		command[0] = "git";
		System.arraycopy(args, 0, command, 1, args.length);
		Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
		assertThat(process.waitFor()).isEqualTo(0);
	}

}
