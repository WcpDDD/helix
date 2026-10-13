package dev.helix.board;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.helix.work.ProjectWorkspaces;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceSkillService {

	private final ProjectWorkspaces workspaces;

	public WorkspaceSkillService(ProjectWorkspaces workspaces) {
		this.workspaces = workspaces;
	}

	public List<SkillIndex.SkillView> list(String accessToken, ProjectEntity project) {
		Path root = workspaces.require(project, accessToken);
		if (!Files.isDirectory(root.resolve("skills"))) {
			return List.of();
		}
		return SkillIndex.fromPaths(workspaces.markdownUnder(root, "skills"));
	}

	public SkillFile file(String accessToken, ProjectEntity project, String path) {
		String safe;
		try {
			safe = SkillIndex.require(path);
		}
		catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		String markdown = workspaces.read(workspaces.require(project, accessToken), safe);
		return new SkillFile(safe, markdown);
	}

	public record SkillFile(String path, String markdown) {
	}

}
