package dev.helix.worker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import dev.helix.agentkit.AgentKitProperties;
import dev.helix.agentkit.AgentKitRuntime;

public final class WorkerProcess {

	private static final Duration BEAT = Duration.ofSeconds(5);

	public static void main(String[] args) throws Exception {
		String server = setting("HELIX_SERVER", "http://127.0.0.1:8080");
		String id = setting("HELIX_WORKER_ID", "worker-local");
		String name = setting("HELIX_WORKER_NAME", "本机");
		int port = Integer.parseInt(setting("HELIX_WORKER_PORT", "8091"));
		Path root = Path.of(setting("HELIX_WORKER_ROOT", System.getProperty("user.home") + "/helix-workers"));
		AgentKitRuntime runtime = new AgentKitRuntime(new AgentKitProperties(
				null, setting("AGENTKIT_MODEL", ""), setting("AGENTKIT_BASE_URL", ""), setting("AGENTKIT_API_KEY", ""), null),
				root.resolve("agent-sessions"));
		WorkerSessions sessions = new WorkerSessions(root, System.getenv(), (thread, message, workspace, gitlab, sink) -> runtime.begin(
				thread, "你在这张 issue 独享的工作区里。只分析仓库和这张 issue。", id, message, workspace, gitlab, sink));
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		http.createContext("/", exchange -> WorkerGateway.handle(exchange, sessions));
		http.start();
		String address = "http://127.0.0.1:" + http.getAddress().getPort();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> http.stop(0)));
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		System.out.println("worker " + id + " 监听 " + address);
		while (true) {
			beat(client, server, id, name, address);
			Thread.sleep(BEAT);
		}
	}

	private static void beat(HttpClient client, String server, String id, String name, String address) {
		String payload = "{\"id\":\"" + json(id) + "\",\"name\":\"" + json(name) + "\",\"address\":\"" + json(address) + "\"}";
		HttpRequest request = HttpRequest.newBuilder(URI.create(server + "/api/workers/heartbeat"))
				.timeout(Duration.ofSeconds(3))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload))
				.build();
		try {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() >= 300) {
				System.out.println("心跳没有登记，状态 " + response.statusCode());
				return;
			}
			System.out.println("心跳已登记");
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			System.out.println("心跳没有登记，" + exception.getMessage());
		}
	}

	private static String json(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String setting(String key, String fallback) {
		String value = System.getenv(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value.strip();
	}

}
