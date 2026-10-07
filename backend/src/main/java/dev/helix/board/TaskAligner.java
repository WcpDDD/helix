package dev.helix.board;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.config.HelixProperties;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class TaskAligner {

	private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

	private final TaskStatusService statuses;

	private final WorkspaceBoard boards;

	private final GitlabSessionCredential credential;

	private final ScheduledExecutorService executor;

	private final AtomicBoolean running = new AtomicBoolean(false);

	public TaskAligner(TaskStatusService statuses, WorkspaceBoard boards, GitlabSessionCredential credential,
			HelixProperties properties) {
		this.statuses = statuses;
		this.boards = boards;
		this.credential = credential;
		Duration interval = properties.alignInterval() == null ? DEFAULT_INTERVAL : properties.alignInterval();
		long delay = Math.max(1, interval.toMillis());
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "helix-task-align");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(this::tick, delay, delay, TimeUnit.MILLISECONDS);
	}

	void tick() {
		String accessToken = credential.current();
		if (accessToken == null || accessToken.isBlank()) {
			return;
		}
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			boards.refresh(accessToken);
			statuses.align(accessToken);
		}
		finally {
			running.set(false);
		}
	}

	@PreDestroy
	void close() {
		executor.shutdown();
	}

}
