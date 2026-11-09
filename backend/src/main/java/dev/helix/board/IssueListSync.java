package dev.helix.board;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.auth.LoginBootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;

@Component
public class IssueListSync {

	static final int INTERVAL_SECONDS = 10;

	private static final Logger log = LoggerFactory.getLogger(IssueListSync.class);

	private final LoginBootstrap logins;

	private final GitlabSessionCredential credential;

	private final ProjectRepository projects;

	private final WorkspaceBoard boards;

	private final TaskBoardService tasks;

	private final ScheduledExecutorService executor;

	private final AtomicBoolean running = new AtomicBoolean(false);

	public IssueListSync(LoginBootstrap logins, GitlabSessionCredential credential, ProjectRepository projects,
			WorkspaceBoard boards, TaskBoardService tasks) {
		this.logins = logins;
		this.credential = credential;
		this.projects = projects;
		this.boards = boards;
		this.tasks = tasks;
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "helix-issue-list");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(this::tick, 1, INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	void tick() {
		if (boards.pinnedClasspath() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			logins.restore();
			String accessToken = credential.current();
			if (accessToken == null || accessToken.isBlank()) {
				return;
			}
			for (ProjectEntity project : projects.findAll()) {
				if (blank(project.getGitHost()) || blank(project.getGitOwner()) || blank(project.getGitRepo())) {
					continue;
				}
				try {
					tasks.pull(project, accessToken);
				}
				catch (ResponseStatusException exception) {
					log.warn("没有同步 {} 的 issue 列表: {}", project.getName(), exception.getStatusCode().value());
				}
				catch (RuntimeException exception) {
					log.warn("没有同步 {} 的 issue 列表", project.getName());
				}
			}
		}
		catch (RuntimeException exception) {
			log.warn("这一轮 issue 列表没有同步完");
		}
		finally {
			running.set(false);
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	@PreDestroy
	void close() {
		executor.shutdownNow();
	}

}
