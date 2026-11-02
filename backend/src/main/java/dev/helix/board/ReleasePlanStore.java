package dev.helix.board;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.helix.work.ProjectWorkspaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReleasePlanStore {

	private static final Logger log = LoggerFactory.getLogger(ReleasePlanStore.class);

	private final ProjectWorkspaces workspaces;

	private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

	public ReleasePlanStore(ProjectWorkspaces workspaces) {
		this.workspaces = workspaces;
	}

	public Map<Integer, LocalDate> read(ProjectEntity project) {
		Path file = file(project);
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try {
			return Map.copyOf(ReleasePlanFile.parse(Files.readString(file)));
		}
		catch (IOException | IllegalArgumentException exception) {
			log.warn("没有读到 {} 的发布计划: {}", project.getName(), exception.getMessage());
			return Map.of();
		}
	}

	public void save(ProjectEntity project, int issue, LocalDate date) {
		if (issue <= 0 || date == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发布日期不对。");
		}
		Path file = file(project);
		synchronized (lock(file)) {
			Map<Integer, LocalDate> dates = new LinkedHashMap<>(load(file));
			dates.put(issue, date);
			write(file, ReleasePlanFile.render(dates));
		}
	}

	private Map<Integer, LocalDate> load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(ReleasePlanFile.parse(Files.readString(file)));
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发布计划没有读到。");
		}
		catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发布计划文件不能用。");
		}
	}

	private void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".release-plan-", ".tmp");
			Files.writeString(temporary, text);
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException exception) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发布计划没有写好。");
		}
	}

	private Path file(ProjectEntity project) {
		return workspaces.directory(project).resolve("release-plan.yaml");
	}

	private Object lock(Path file) {
		return locks.computeIfAbsent(file.toString(), key -> new Object());
	}

}
