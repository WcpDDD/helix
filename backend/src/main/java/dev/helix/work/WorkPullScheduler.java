package dev.helix.work;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.config.HelixProperties;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;

@Component
public class WorkPullScheduler {

	static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	private static final String DEFAULT_TIMES = "09:00,13:00,18:00";

	private final ProjectWorkspaces workspaces;

	private final ProjectRepository projects;

	private final GitlabSessionCredential credential;

	private final Set<LocalTime> times;

	private final ScheduledExecutorService executor;

	private LocalDateTime fired;

	public WorkPullScheduler(ProjectWorkspaces workspaces, ProjectRepository projects, GitlabSessionCredential credential,
			HelixProperties properties) {
		this.workspaces = workspaces;
		this.projects = projects;
		this.credential = credential;
		String configured = properties == null ? null : properties.workPullTimes();
		this.times = timesOf(configured == null || configured.isBlank() ? DEFAULT_TIMES : configured);
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "helix-work-pull");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(this::tick, 20, 20, TimeUnit.SECONDS);
	}

	void tick() {
		fire(LocalDateTime.now(ZONE));
	}

	void fire(LocalDateTime now) {
		LocalDateTime mark = slot(now, times);
		if (mark == null || mark.equals(fired)) {
			return;
		}
		String token = credential.current();
		if (token == null || token.isBlank()) {
			return;
		}
		fired = mark;
		for (ProjectEntity project : projects.findAll()) {
			if (workspaces.opened(project) == null) {
				continue;
			}
			try {
				workspaces.requestPull(project, token);
			}
			catch (ResponseStatusException exception) {
				// 这个目录正在拉取或推送，这一分钟跳过。
			}
		}
	}

	static Set<LocalTime> timesOf(String text) {
		Set<LocalTime> parsed = new LinkedHashSet<>();
		for (String part : text.split(",")) {
			String item = part.strip();
			if (item.isEmpty()) {
				continue;
			}
			parsed.add(LocalTime.parse(item));
		}
		return Set.copyOf(parsed);
	}

	static LocalDateTime slot(LocalDateTime now, Set<LocalTime> times) {
		LocalTime minute = LocalTime.of(now.getHour(), now.getMinute());
		if (!times.contains(minute)) {
			return null;
		}
		return now.toLocalDate().atTime(minute);
	}

	@PreDestroy
	void close() {
		executor.shutdownNow();
	}

}
