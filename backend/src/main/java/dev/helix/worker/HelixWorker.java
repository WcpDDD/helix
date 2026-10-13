package dev.helix.worker;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.helix.agentkit.AgentKitProperties;
import dev.helix.agentkit.AgentKitRuntime;
import dev.helix.agentkit.AgentKitRuntime.RunResult;
import dev.helix.worker.SubtaskScheduler.Dispatch;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

public final class HelixWorker {

	private final RestClient http;

	private final String id;

	private final String name;

	private final Path root;

	private final AgentKitRuntime runtime;

	public HelixWorker(URI server, String id, String name, Path root, AgentKitProperties properties) {
		this.http = RestClient.builder().baseUrl(server).build();
		this.id = id;
		this.name = name;
		this.root = root.toAbsolutePath().normalize();
		this.runtime = new AgentKitRuntime(properties);
	}

	public void heartbeat() {
		http.post().uri("/api/workers/heartbeat").body(new Heartbeat(id, name)).retrieve().toBodilessEntity();
	}

	public int pollAndRun() {
		List<Dispatch> waiting = http.get().uri("/api/workers/{id}/dispatches", id).retrieve()
				.body(new ParameterizedTypeReference<List<Dispatch>>() {
				});
		if (waiting == null || waiting.isEmpty()) {
			return 0;
		}
		for (Dispatch dispatch : waiting) {
			runOne(dispatch);
		}
		return waiting.size();
	}

	private void runOne(Dispatch dispatch) {
		Path workspace = AgentKitRuntime.resolveWorkspace(root, "subtask-" + dispatch.issueId());
		boolean bound = false;
		try {
			Files.createDirectories(workspace);
			http.post().uri("/api/workers/{id}/bindings", id)
					.body(new Binding(dispatch.issueId(), workspace.toString()))
					.retrieve().toBodilessEntity();
			bound = true;
			RunResult result = runtime.execute(dispatch.systemPrompt(), id, dispatch.message(), workspace);
			report(dispatch.issueId(), result.phase(), result.text(), result.failure());
		}
		catch (RuntimeException exception) {
			if (bound) {
				report(dispatch.issueId(), "FAILED", null, exception.getMessage());
			}
			throw exception;
		}
		catch (Exception exception) {
			if (bound) {
				report(dispatch.issueId(), "FAILED", null, exception.getMessage());
			}
			throw new IllegalStateException(exception.getMessage(), exception);
		}
	}

	private void report(long issueId, String phase, String text, String failure) {
		try {
			http.post().uri("/api/workers/{id}/subtasks/{issueId}/result", id, issueId)
					.body(new Result(phase, text, failure))
					.retrieve().toBodilessEntity();
		}
		catch (RuntimeException ignored) {
			// 结果没写上时，保留原来的执行异常。
		}
	}

	private record Heartbeat(String id, String name) {
	}

	private record Binding(long issueId, String workspace) {
	}

	private record Result(String phase, String text, String failure) {
	}

}
