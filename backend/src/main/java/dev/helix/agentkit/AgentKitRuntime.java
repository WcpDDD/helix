package dev.helix.agentkit;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.agentkit.api.execution.Caller;
import io.agentkit.api.execution.ExecutionEvent;
import io.agentkit.api.execution.ExecutionRejectedException;
import io.agentkit.api.execution.Task;
import io.agentkit.api.execution.WorkspaceOpening;
import io.agentkit.api.react.ReActConstructor;
import io.agentkit.execution.env.local.LocalFsEnvProvider;
import io.agentkit.execution.facade.ExecutionFacade;
import io.agentkit.execution.meta.EnvPrimitiveTools;
import io.agentkit.execution.react.DefaultReActConstructor;
import io.agentkit.infra.llm.OpenAICompatibleBackend;
import io.agentkit.infra.llm.OpenAICompatibleConfig;
import io.agentkit.infra.llm.RoutingProvider;
import io.agentkit.infra.llm.StaticLLMConfigProvider;
import io.agentkit.infra.llm.TencentMaasBackend;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class AgentKitRuntime {

	private static final Duration WAIT = Duration.ofMinutes(10);

	private final AgentKitProperties properties;

	public AgentKitRuntime(AgentKitProperties properties) {
		this.properties = properties;
	}

	public RunResult execute(String systemPrompt, String callerId, String message, Path workspace) {
		if (!properties.ready()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AGENTKIT_MODEL 和 AGENTKIT_API_KEY 还没配。");
		}
		String prompt = message == null ? "" : message.strip();
		if (prompt.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
		}
		Path root = workspace.toAbsolutePath().normalize();
		String threadId = UUID.randomUUID().toString();
		String taskId = UUID.randomUUID().toString();
		Caller caller = new Caller(callerId, null, null);
		ExecutionFacade execution = facade(systemPrompt);
		WorkspaceOpening opening = new WorkspaceOpening(
				"helix", root.toString(), false, Map.of(), Duration.ofSeconds(60), 256 * 1024, Map.of());
		CompletableFuture<ExecutionEvent.RunEnded> done = new CompletableFuture<>();
		try {
			execution.submit(caller, new Task(
					threadId, taskId, prompt,
					null, null, null, null, null, null,
					properties.turns(), null, null, opening));
			watch(execution, caller, threadId, done);
			ExecutionEvent.RunEnded ended = done.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
			return new RunResult(threadId, ended.phase().name(), ended.assistantText(), ended.failureMessage());
		}
		catch (TimeoutException exception) {
			throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "这轮跑超时了。");
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
		catch (ExecutionRejectedException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
		}
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

	private ExecutionFacade facade(String systemPrompt) {
		String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
				? "https://api.openai.com/v1"
				: properties.baseUrl().strip();
		StaticLLMConfigProvider llmConfig = new StaticLLMConfigProvider(new OpenAICompatibleConfig(
				"openai_compatible", baseUrl, properties.apiKey().strip(), Set.of(), 0, 0, 0, 0));
		RoutingProvider llm = new RoutingProvider(List.of(
				new TencentMaasBackend(llmConfig),
				new OpenAICompatibleBackend(llmConfig)));
		ReActConstructor constructor = new DefaultReActConstructor(llm, new EnvPrimitiveTools(), List.of());
		return new ExecutionFacade(constructor, new LocalFsEnvProvider(), properties.model().strip(),
				systemPrompt == null ? "" : systemPrompt, properties.turns());
	}

	private static void watch(ExecutionFacade execution, Caller caller, String threadId,
			CompletableFuture<ExecutionEvent.RunEnded> done) {
		execution.events(caller, threadId).subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ExecutionEvent event) {
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

	public record RunResult(String threadId, String phase, String text, String failure) {
	}

}
