package dev.helix.worker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerSpecTest {

	@TempDir
	Path temp;

	@Test
	void readsTheChangeBranchAsTheSpecName() throws Exception {
		Path space = temp.resolve("issue-21");
		Files.createDirectories(space.resolve("openspec/changes/docs-smart-fill/specs/fill"));
		Files.writeString(space.resolve("openspec/changes/docs-smart-fill/proposal.md"), "# 提案");
		Files.writeString(space.resolve("openspec/changes/docs-smart-fill/specs/fill/spec.md"), "# 规格");
		git(space, "init", "-b", "docs/smart-fill");
		git(space, "add", "-A");
		git(space, "-c", "user.name=Helix", "-c", "user.email=helix@localhost", "commit", "-m", "规格");

		WorkerSessions.Result result = new WorkerSpec(temp).read(21);

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body()).contains("\"branch\":\"docs/smart-fill\"");
		assertThat(result.body()).contains("openspec/changes/docs-smart-fill/proposal.md");
		assertThat(result.body()).contains("openspec/changes/docs-smart-fill/specs/fill/spec.md");
		assertThat(result.body()).contains("# 提案");
	}

	@Test
	void worktreeBranchIsNotASpec() throws Exception {
		Path space = temp.resolve("issue-21");
		Files.createDirectories(space);
		git(space, "init", "-b", "helix/issue-21");
		git(space, "-c", "user.name=Helix", "-c", "user.email=helix@localhost", "commit", "--allow-empty", "-m", "空");

		WorkerSessions.Result result = new WorkerSpec(temp).read(21);

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body()).contains("\"branch\":\"helix/issue-21\"");
		assertThat(result.body()).contains("\"files\":[]");
	}

	@Test
	void gatewayServesTheIssueSpec() throws Exception {
		Path space = temp.resolve("issue-21");
		Files.createDirectories(space.resolve("openspec/changes/fix-load"));
		Files.writeString(space.resolve("openspec/changes/fix-load/design.md"), "# 设计");
		git(space, "init", "-b", "fix/load");
		git(space, "add", "-A");
		git(space, "-c", "user.name=Helix", "-c", "user.email=helix@localhost", "commit", "-m", "设计");
		WorkerSessions sessions = new WorkerSessions(temp, Map.of(), (thread, message, workspace, gitlab, sink, profile) -> {
		});
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> WorkerGateway.handle(exchange, sessions));
		server.start();
		try {
			HttpResponse<String> response = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sessions/21/spec"))
							.GET()
							.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("openspec/changes/fix-load/design.md");
		}
		finally {
			server.stop(0);
		}
	}

	private static void git(Path directory, String... args) throws Exception {
		java.util.List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.addAll(java.util.List.of(args));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		if (process.waitFor() != 0) {
			throw new IllegalStateException(output);
		}
	}

}
