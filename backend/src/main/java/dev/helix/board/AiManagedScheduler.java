package dev.helix.board;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.auth.LoginBootstrap;
import dev.helix.config.HelixProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class AiManagedScheduler {

	private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(AiManagedScheduler.class);

	private final LoginBootstrap logins;

	private final GitlabSessionCredential credential;

	private final AiManagedScan scan;

	private final ScheduleLog journal;

	private final ScheduledExecutorService executor;

	private final AtomicBoolean running = new AtomicBoolean(false);

	public AiManagedScheduler(LoginBootstrap logins, GitlabSessionCredential credential, AiManagedScan scan,
			ScheduleLog journal, HelixProperties properties) {
		this.logins = logins;
		this.credential = credential;
		this.scan = scan;
		this.journal = journal;
		Duration interval = properties.alignInterval() == null ? DEFAULT_INTERVAL : properties.alignInterval();
		long delay = Math.max(1, interval.toMillis());
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "helix-ai-managed");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(this::tick, delay, delay, TimeUnit.MILLISECONDS);
	}

	void tick() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			logins.restore();
			String accessToken = credential.current();
			if (accessToken == null || accessToken.isBlank()) {
				journal.write("idle", java.util.Map.of("reason", "没有登录"));
				return;
			}
			scan.run(accessToken);
		}
		catch (RuntimeException exception) {
			log.warn("托管这一轮没有跑完");
			journal.write("tick-failed", java.util.Map.of());
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
