package dev.helix.board;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.helix.config.HelixProperties;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.GitlabRepositoryClient;
import dev.helix.work.ProjectWorkspaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceBoard {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceBoard.class);

	private static final String FILE = "board.yaml";

	private final HelixProperties properties;

	private final ProjectRepository projects;

	private final GitlabRepositoryClient gitlab;

	private final ProjectWorkspaces workspaces;

	private final ConcurrentMap<Long, BoardFile> cache = new ConcurrentHashMap<>();

	private volatile BoardFile local;

	private volatile long localStamp = Long.MIN_VALUE;

	public WorkspaceBoard(HelixProperties properties, ProjectRepository projects, GitlabRepositoryClient gitlab,
			ProjectWorkspaces workspaces) {
		this.properties = properties;
		this.projects = projects;
		this.gitlab = gitlab;
		this.workspaces = workspaces;
	}

	public boolean pinnedClasspath() {
		String location = location();
		return location != null && location.startsWith("classpath:");
	}

	public BoardFile structure(ProjectEntity project, String token) {
		String location = location();
		if (location != null) {
			return readLocal(location);
		}
		Path directory = workspaces.await(project, token);
		return readCheckout(directory.resolve(FILE));
	}

	public BoardFile current(long projectId) {
		String location = location();
		if (location != null) {
			return readLocal(location);
		}
		ProjectEntity project = projects.findById(projectId).orElse(null);
		if (project != null) {
			Path directory = workspaces.opened(project);
			if (directory != null) {
				return readCheckout(directory.resolve("board.yaml"));
			}
		}
		return cache.getOrDefault(projectId, BoardFile.empty());
	}

	public void refresh(String accessToken) {
		if (location() != null || accessToken == null || accessToken.isBlank()) {
			return;
		}
		for (ProjectEntity project : projects.findAll()) {
			try {
				String yaml = gitlab.file(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(),
						FILE);
				cache.put(project.getId(), BoardFile.parse(yaml));
			}
			catch (GitlabIssueException exception) {
				if (exception.status() == 404) {
					cache.put(project.getId(), BoardFile.empty());
					continue;
				}
				log.warn("没有读到 {} 的 board.yaml: {}", project.getName(), exception.status());
			}
			catch (IllegalArgumentException exception) {
				log.warn("{} 的 board.yaml 不能用: {}", project.getName(), exception.getMessage());
			}
		}
	}

	private BoardFile readCheckout(Path path) {
		if (!Files.isRegularFile(path)) {
			return BoardFile.empty();
		}
		try {
			return BoardFile.parse(Files.readString(path));
		}
		catch (IOException exception) {
			throw new IllegalStateException("board.yaml 没有读到", exception);
		}
		catch (IllegalArgumentException exception) {
			log.warn("工作目录里的 board.yaml 不能用: {}", exception.getMessage());
			return BoardFile.empty();
		}
	}

	private BoardFile readLocal(String location) {
		if (location.startsWith("classpath:")) {
			BoardFile snapshot = local;
			if (snapshot != null) {
				return snapshot;
			}
			snapshot = BoardFile.parse(readClasspath(location.substring("classpath:".length())));
			local = snapshot;
			return snapshot;
		}
		Path path = Path.of(location.startsWith("file:") ? location.substring("file:".length()) : location);
		try {
			long stamp = Files.getLastModifiedTime(path).toMillis();
			BoardFile snapshot = local;
			if (snapshot != null && stamp == localStamp) {
				return snapshot;
			}
			snapshot = BoardFile.parse(Files.readString(path));
			local = snapshot;
			localStamp = stamp;
			return snapshot;
		}
		catch (IOException exception) {
			throw new IllegalStateException("board.yaml 没有读到", exception);
		}
	}

	private static String readClasspath(String path) {
		ClassPathResource resource = new ClassPathResource(path.startsWith("/") ? path.substring(1) : path);
		try (InputStream input = resource.getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new IllegalStateException("board.yaml 没有读到", exception);
		}
	}

	private String location() {
		String location = properties.boardFile();
		if (location == null || location.isBlank()) {
			return null;
		}
		return location.trim();
	}

}
