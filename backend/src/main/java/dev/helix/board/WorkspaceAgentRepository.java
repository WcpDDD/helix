package dev.helix.board;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import dev.helix.work.ProjectWorkspaces;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class WorkspaceAgentRepository implements AgentRepository {

	private final ProjectRepository projects;

	private final ProjectWorkspaces workspaces;

	private final JdbcTemplate jdbc;

	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	public WorkspaceAgentRepository(ProjectRepository projects, ProjectWorkspaces workspaces, JdbcTemplate jdbc) {
		this.projects = projects;
		this.workspaces = workspaces;
		this.jdbc = jdbc;
	}

	@Override
	public List<AgentEntity> findByProject_IdOrderByNameAsc(Long projectId) {
		return load(projectId);
	}

	@Override
	public List<AgentEntity> findWithSkills(Long projectId) {
		return load(projectId);
	}

	@Override
	public Optional<AgentEntity> findByProject_IdAndName(Long projectId, String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return load(projectId).stream().filter(agent -> name.equals(agent.getName())).findFirst();
	}

	@Override
	public Optional<AgentEntity> findByProject_IdAndTag(Long projectId, String tag) {
		return findWithSkillsByTag(projectId, tag);
	}

	@Override
	public Optional<AgentEntity> findWithSkillsByName(Long projectId, String name) {
		return findByProject_IdAndName(projectId, name);
	}

	@Override
	public Optional<AgentEntity> findWithSkillsByTag(Long projectId, String tag) {
		if (tag == null || tag.isBlank()) {
			return Optional.empty();
		}
		return load(projectId).stream().filter(agent -> tag.equals(agent.getTag())).findFirst();
	}

	@Override
	public boolean existsByProject_IdAndName(Long projectId, String name) {
		return load(projectId).stream().anyMatch(agent -> agent.getName().equals(name));
	}

	@Override
	public boolean existsByProject_IdAndTag(Long projectId, String tag) {
		return findWithSkillsByTag(projectId, tag).isPresent();
	}

	@Override
	public boolean existsByProject_IdAndTagAndNameNot(Long projectId, String tag, String name) {
		return findWithSkillsByTag(projectId, tag).filter(agent -> !agent.getName().equals(name)).isPresent();
	}

	@Override
	public List<AgentEntity> findAll() {
		List<AgentEntity> all = new ArrayList<>();
		for (ProjectEntity project : projects.findAll()) {
			all.addAll(load(project.getId()));
		}
		return List.copyOf(all);
	}

	@Override
	public AgentEntity save(AgentEntity agent) {
		return save(agent, agent.getName());
	}

	@Override
	public AgentEntity save(AgentEntity agent, String previousName) {
		ProjectEntity project = agent.getProject();
		Path directory = workspaces.directory(project);
		synchronized (lock(directory)) {
			prepare(project, directory);
			if (Files.isDirectory(directory.resolve(".git"))) {
				workspaces.include(directory, "agents");
			}
			Path target = folder(directory, agent.getName());
			write(target.resolve("agent.md"), agent);
			if (previousName != null && !previousName.equals(agent.getName()) && folderName(previousName)) {
				deleteTree(folder(directory, previousName));
			}
			return agent;
		}
	}

	@Override
	public void delete(AgentEntity agent) {
		if (agent.getProject() == null || agent.getName() == null) {
			return;
		}
		Path directory = workspaces.directory(agent.getProject());
		synchronized (lock(directory)) {
			deleteTree(folder(directory, agent.getName()));
		}
	}

	private List<AgentEntity> load(Long projectId) {
		if (projectId == null) {
			return List.of();
		}
		ProjectEntity project = projects.findById(projectId).orElse(null);
		if (project == null) {
			return List.of();
		}
		Path directory = workspaces.directory(project);
		synchronized (lock(directory)) {
			prepare(project, directory);
			return read(project, directory);
		}
	}

	private void prepare(ProjectEntity project, Path directory) {
		if (!Files.isDirectory(directory.resolve(".git"))) {
			return;
		}
		migrate(project, directory);
	}

	private void migrate(ProjectEntity project, Path directory) {
		List<Stored> rows = stored(project.getId());
		if (rows.isEmpty()) {
			return;
		}
		List<AgentEntity> present = read(project, directory);
		List<String> names = present.stream().map(AgentEntity::getName).toList();
		List<Long> exported = new ArrayList<>();
		boolean writing = rows.stream().anyMatch(row -> !names.contains(row.name()) && folderName(row.name()));
		if (writing) {
			workspaces.include(directory, "agents");
		}
		for (Stored row : rows) {
			if (names.contains(row.name())) {
				exported.add(row.id());
				continue;
			}
			if (!folderName(row.name())) {
				continue;
			}
			AgentEntity agent = new AgentEntity(project, row.name(), row.body() == null ? "" : row.body());
			agent.retag(row.tag());
			agent.replaceSkills(row.skills());
			write(folder(directory, row.name()).resolve("agent.md"), agent);
			exported.add(row.id());
		}
		for (Long id : exported) {
			forget(id);
		}
	}

	private List<Stored> stored(Long projectId) {
		if (projectId == null || !table("agent")) {
			return List.of();
		}
		List<Stored> rows = jdbc.query(
				"select id, name, body, tag from agent where project_id = ? order by id",
				(result, row) -> new Stored(result.getLong("id"), result.getString("name"), result.getString("body"),
						result.getString("tag"), new ArrayList<>()),
				projectId);
		if (rows.isEmpty() || !table("agent_skill")) {
			return rows;
		}
		jdbc.query("""
				select agent_id, skill_path from agent_skill
				where agent_id in (select id from agent where project_id = ?)
				order by position, id
				""", result -> {
			long agentId = result.getLong("agent_id");
			String path = result.getString("skill_path");
			for (Stored row : rows) {
				if (row.id() == agentId && path != null && !path.isBlank()) {
					row.skills().add(path);
					break;
				}
			}
		}, projectId);
		return rows;
	}

	private boolean table(String name) {
		Number count = jdbc.queryForObject(
				"select count(*) from sqlite_master where type = 'table' and name = ?", Number.class, name);
		return count != null && count.intValue() > 0;
	}

	private void forget(long id) {
		if (!table("agent")) {
			return;
		}
		if (table("agent_skill")) {
			jdbc.update("delete from agent_skill where agent_id = ?", id);
		}
		jdbc.update("delete from agent where id = ?", id);
	}

	private List<AgentEntity> read(ProjectEntity project, Path directory) {
		Path root = directory.resolve("agents");
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<AgentEntity> found = new ArrayList<>();
		try (var listed = Files.list(root)) {
			for (Path child : listed.filter(Files::isDirectory).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
				Path file = child.resolve("agent.md");
				if (!Files.isRegularFile(file) || !file.normalize().startsWith(root)) {
					continue;
				}
				String name = child.getFileName().toString();
				if (!folderName(name)) {
					continue;
				}
				String text = Files.readString(file);
				AgentEntity agent = new AgentEntity(project, name, "");
				AgentDocument.readInto(agent, text);
				if (text.contains("\nid:")) {
					write(file, agent);
				}
				if (found.stream().noneMatch(item -> item.getName().equals(agent.getName()))) {
					found.add(agent);
				}
			}
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "agent 文件没有读到。");
		}
		found.sort(Comparator.comparing(AgentEntity::getName));
		return List.copyOf(found);
	}

	private static void write(Path file, AgentEntity agent) {
		Path folder = file.getParent();
		try {
			Files.createDirectories(folder);
			Path temporary = Files.createTempFile(folder, ".agent-", ".tmp");
			Files.writeString(temporary, AgentDocument.render(agent));
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException exception) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "agent 文件没有写好。");
		}
	}

	private static Path folder(Path directory, String name) {
		if (!folderName(name)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名字不能包含路径。");
		}
		Path folder = directory.resolve("agents").resolve(name).normalize();
		if (!folder.startsWith(directory.resolve("agents"))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名字不能包含路径。");
		}
		return folder;
	}

	private static boolean folderName(String name) {
		return name != null && !name.isBlank() && !name.contains("/") && !name.contains("\\") && !".".equals(name)
				&& !"..".equals(name);
	}

	private static void deleteTree(Path root) {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException exception) {
					throw new UncheckedIOException(exception);
				}
			});
		}
		catch (IOException | UncheckedIOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "agent 文件没有删掉。");
		}
	}

	private Object lock(Path directory) {
		return locks.computeIfAbsent(directory.toString(), key -> new Object());
	}

	private record Stored(long id, String name, String body, String tag, List<String> skills) {
	}

}
