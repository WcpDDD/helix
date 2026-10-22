package dev.helix.agentkit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

import io.agentkit.api.execution.Caller;
import io.agentkit.api.execution.ExecutionChunk;
import io.agentkit.api.execution.ExecutionEvent;
import io.agentkit.api.execution.ExecutionRejectedException;
import io.agentkit.api.execution.Task;
import io.agentkit.api.execution.WorkspaceOpening;
import io.agentkit.api.react.ReActConstructor;
import io.agentkit.api.session.SessionStorage;
import io.agentkit.execution.assembly.AssetInstalling;
import io.agentkit.execution.env.local.LocalFsEnvProvider;
import io.agentkit.execution.facade.ExecutionFacade;
import io.agentkit.execution.meta.EnvPrimitiveTools;
import io.agentkit.execution.react.DefaultReActConstructor;
import io.agentkit.execution.session.DefaultSessionService;
import io.agentkit.infra.llm.OpenAICompatibleBackend;
import io.agentkit.infra.llm.OpenAICompatibleConfig;
import io.agentkit.infra.llm.RoutingProvider;
import io.agentkit.infra.llm.StaticLLMConfigProvider;
import io.agentkit.infra.llm.TencentMaasBackend;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class AgentKitRuntime {

	private static final Pattern THREAD_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final AgentKitProperties properties;

	private final SessionStorage memory;

	private final Map<String, Bound> sessions = new ConcurrentHashMap<>();

	private volatile ExternalAssets assets;

	public AgentKitRuntime(AgentKitProperties properties) {
		this(properties, null);
	}

	public AgentKitRuntime(AgentKitProperties properties, Path memory) {
		this.properties = properties;
		this.memory = SessionMemory.open(memory);
	}

	public RunResult execute(String systemPrompt, String callerId, String message, Path workspace) {
		return execute(systemPrompt, callerId, message, workspace, Map.of());
	}

	public RunResult execute(String systemPrompt, String callerId, String message, Path workspace,
			Map<String, String> userEnv) {
		Started started = launch(systemPrompt, callerId, message, workspace, userEnv);
		try {
			ExecutionEvent.RunEnded ended = started.done().get();
			return new RunResult(started.threadId(), ended.phase().name(), ended.assistantText(), ended.failureMessage());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "这轮被打断了。");
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof ResponseStatusException status) {
				throw status;
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, cause.getMessage());
		}
	}

	public void begin(String systemPrompt, String callerId, String message, Path workspace, Map<String, String> userEnv) {
		begin(systemPrompt, callerId, message, workspace, userEnv, null);
	}

	public void begin(String systemPrompt, String callerId, String message, Path workspace, Map<String, String> userEnv,
			RunSink sink) {
		begin(null, systemPrompt, callerId, message, workspace, userEnv, sink);
	}

	public void begin(String threadId, String systemPrompt, String callerId, String message, Path workspace,
			Map<String, String> userEnv, RunSink sink) {
		begin(threadId, systemPrompt, callerId, message, workspace, userEnv, sink, "", List.of());
	}

	public void begin(String threadId, String systemPrompt, String callerId, String message, Path workspace,
			Map<String, String> userEnv, RunSink sink, String agentsMd, List<String> skills) {
		Started started = launch(threadId, systemPrompt, callerId, message, workspace, userEnv, sink, agentsMd, skills);
		Thread thread = new Thread(() -> await(started, sink), "helix-worker-run");
		thread.setDaemon(true);
		thread.start();
	}

	private void await(Started started, RunSink sink) {
		try {
			ExecutionEvent.RunEnded ended = started.done().get();
			System.out.println("任务结束 " + started.threadId() + " " + ended.phase());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			System.out.println("任务被打断 " + started.threadId());
			close(sink, "failed", "这轮被打断了。");
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			System.out.println("任务失败 " + started.threadId() + " " + cause.getMessage());
			close(sink, "failed", cause.getMessage());
		}
	}

	private static void close(RunSink sink, String status, String detail) {
		if (sink == null) {
			return;
		}
		try {
			sink.onClosed(status, detail);
		}
		catch (RuntimeException exception) {
			System.out.println("会话状态没有写上");
		}
	}

	private Started launch(String systemPrompt, String callerId, String message, Path workspace,
			Map<String, String> userEnv) {
		return launch(null, systemPrompt, callerId, message, workspace, userEnv, null, "", List.of());
	}

	private Started launch(String requestedThread, String systemPrompt, String callerId, String message, Path workspace,
			Map<String, String> userEnv, RunSink sink, String agentsMd, List<String> skills) {
		if (!properties.ready()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AGENTKIT_MODEL 和 AGENTKIT_API_KEY 还没配。");
		}
		String prompt = message == null ? "" : message.strip();
		if (prompt.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
		}
		Path root = workspace.toAbsolutePath().normalize();
		String threadId = requestedThread != null && THREAD_ID.matcher(requestedThread).matches()
				? requestedThread
				: UUID.randomUUID().toString();
		String taskId = UUID.randomUUID().toString();
		Caller caller = new Caller(callerId, null, null);
		Bound bound = facade(systemPrompt);
		String extra = agentsMd == null ? "" : agentsMd.strip();
		if (!extra.isEmpty() && bound.prompt().contains(extra)) {
			extra = "";
		}
		Map<String, String> env = userEnv == null ? Map.of() : Map.copyOf(userEnv);
		WorkspaceOpening opening = new WorkspaceOpening(
				root.toString(), root.toString(), env, Duration.ofSeconds(60), 256 * 1024, Map.of());
		CompletableFuture<ExecutionEvent.RunEnded> done = new CompletableFuture<>();
		try {
			bound.execution().prepare(caller, opening);
			try {
				bound.service().maintain();
			}
			catch (RuntimeException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "外部资产没有装上。", exception);
			}
			bound.execution().submit(caller, new Task(
					threadId, taskId, prompt,
					null, null, null, enabled(skills), null, extra,
					properties.turns(), null, null, opening));
			watch(bound.execution(), caller, threadId, done, sink);
			return new Started(threadId, done, bound.execution());
		}
		catch (ExecutionRejectedException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
		}
	}

	private static List<String> enabled(List<String> skills) {
		if (skills == null || skills.isEmpty()) {
			return List.of("helix-none");
		}
		List<String> names = new ArrayList<>();
		for (String skill : skills) {
			if (skill == null) {
				continue;
			}
			String name = skill.strip();
			if (name.isEmpty() || names.contains(name)) {
				continue;
			}
			names.add(name);
		}
		return names.isEmpty() ? List.of("helix-none") : List.copyOf(names);
	}

	public static Path resolveWorkspace(Path root, String requested) {
		Path base = root.toAbsolutePath().normalize();
		if (requested == null || requested.isBlank()) {
			return base;
		}
		Path candidate = Path.of(requested);
		Path resolved = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).toAbsolutePath().normalize();
		if (!resolved.startsWith(base)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区必须在配置的目录里面。");
		}
		return resolved;
	}

	private Bound facade(String systemPrompt) {
		String prompt = systemPrompt == null || systemPrompt.isBlank() ? "你在工作区里。" : systemPrompt;
		return sessions.computeIfAbsent(prompt, this::open);
	}

	private Bound open(String systemPrompt) {
		String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
				? "https://api.openai.com/v1"
				: properties.baseUrl().strip();
		StaticLLMConfigProvider llmConfig = new StaticLLMConfigProvider(new OpenAICompatibleConfig(
				"openai_compatible", baseUrl, properties.apiKey().strip(), Set.of(), 0, 0, 0, 0));
		RoutingProvider llm = new RoutingProvider(List.of(
				new TencentMaasBackend(llmConfig),
				new OpenAICompatibleBackend(llmConfig)));
		ReActConstructor constructor = new DefaultReActConstructor(llm, new EnvPrimitiveTools(), List.of());
		DefaultSessionService service = new DefaultSessionService(
				memory, constructor, properties.model().strip(), systemPrompt, properties.turns(),
				new LocalFsEnvProvider(), assets().mounting(), new AssetInstalling());
		return new Bound(systemPrompt, new ExecutionFacade(service), service);
	}

	private ExternalAssets assets() {
		ExternalAssets current = assets;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (assets == null) {
				try {
					assets = ExternalAssets.open();
				}
				catch (IOException | RuntimeException exception) {
					throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "外部资产目录打不开。", exception);
				}
			}
			return assets;
		}
	}

	private record Bound(String prompt, ExecutionFacade execution, DefaultSessionService service) {
	}

	private static void watch(ExecutionFacade execution, Caller caller, String threadId,
			CompletableFuture<ExecutionEvent.RunEnded> done, RunSink sink) {
		if (sink != null) {
			subscribeChunks(execution, caller, threadId, sink);
		}
		execution.events(caller, threadId).subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ExecutionEvent event) {
				if (sink != null) {
					try {
						sink.onEvent(event);
					}
					catch (RuntimeException exception) {
						System.out.println("会话事件没有写上");
					}
				}
				if (event instanceof ExecutionEvent.RunEnded ended) {
					done.complete(ended);
				}
			}

			@Override
			public void onError(Throwable throwable) {
				done.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				done.completeExceptionally(new IllegalStateException("这轮没有收到结束事件。"));
			}
		});
	}

	private static void subscribeChunks(ExecutionFacade execution, Caller caller, String threadId, RunSink sink) {
		try {
			execution.stream(caller, threadId).subscribe(new Flow.Subscriber<>() {
				@Override
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(ExecutionChunk chunk) {
					try {
						sink.onChunk(chunk);
					}
					catch (RuntimeException exception) {
						System.out.println("会话片段没有写上");
					}
				}

				@Override
				public void onError(Throwable throwable) {
					System.out.println("会话流中断了");
				}

				@Override
				public void onComplete() {
				}
			});
		}
		catch (RuntimeException exception) {
			System.out.println("会话流没有接上");
		}
	}

	public interface RunSink {
		void onEvent(ExecutionEvent event);

		void onChunk(ExecutionChunk chunk);

		default void onClosed(String status, String detail) {
		}
	}

	public record RunResult(String threadId, String phase, String text, String failure) {
	}

	private record Started(String threadId, CompletableFuture<ExecutionEvent.RunEnded> done, ExecutionFacade execution) {
	}

}
