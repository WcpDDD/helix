package dev.helix.board;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.helix.config.HelixProperties;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.GitlabRepositoryClient;
import dev.helix.work.ProjectWorkspaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkspaceBoard {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceBoard.class);

	private static final String FILE = "board.yaml";

	private final HelixProperties properties;

	private final ProjectRepository projects;

	private final GitlabRepositoryClient gitlab;

	private final ProjectWorkspaces workspaces;

	private final ConcurrentMap<Long, BoardFile> cache = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

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

	public void record(ProjectEntity project, String token, int number, String author, List<String> owners) {
		if (pinnedClasspath() || project == null || number <= 0) {
			return;
		}
		String creator = author == null ? null : author.strip();
		if (creator != null && creator.isEmpty()) {
			creator = null;
		}
		if (creator == null && owners == null) {
			return;
		}
		Path file = boardPath(project, token);
		synchronized (lock(file)) {
			String current = readCurrent(project, token, file);
			String next;
			try {
				next = BoardFile.withPeople(current, number, creator, owners);
			}
			catch (IllegalArgumentException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工作目录里的 board.yaml 写不进创建人或负责人。");
			}
			write(file, next);
			local = null;
			localStamp = Long.MIN_VALUE;
		}
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

	private Path boardPath(ProjectEntity project, String token) {
		String location = location();
		if (location == null) {
			return workspaces.await(project, token).resolve(FILE);
		}
		String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
		return Path.of(path);
	}

	private String readCurrent(ProjectEntity project, String token, Path file) {
		if (Files.isRegularFile(file)) {
			try {
				return Files.readString(file);
			}
			catch (IOException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工作目录里的 board.yaml 没有读到。");
			}
		}
		if (location() != null) {
			return "";
		}
		try {
			return gitlab.file(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), FILE);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 404) {
				return "";
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工作目录里的 board.yaml 没有读到。");
		}
	}

	private void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".board-", ".tmp");
			Files.writeString(temporary, text);
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException exception) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工作目录里的 board.yaml 没有写好。");
		}
	}

	private Object lock(Path file) {
		return locks.computeIfAbsent(file.toString(), key -> new Object());
	}

	private String location() {
		String location = properties.boardFile();
		if (location == null || location.isBlank()) {
			return null;
		}
		return location.trim();
	}

}
