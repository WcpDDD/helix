package dev.helix.board;

import java.util.List;

import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.GitlabRepositoryClient;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceSkillService {

	private final GitlabRepositoryClient repository;

	public WorkspaceSkillService(GitlabRepositoryClient repository) {
		this.repository = repository;
	}

	public List<SkillIndex.SkillView> list(String accessToken, ProjectEntity project) {
		String branch = branch(accessToken, project);
		try {
			List<String> blobs = repository.blobs(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), "skills", branch);
			if (blobs == null) {
				return List.of();
			}
			return SkillIndex.fromPaths(blobs);
		}
		catch (GitlabIssueException exception) {
			throw failure(exception);
		}
	}

	public SkillFile file(String accessToken, ProjectEntity project, String path) {
		String safe;
		try {
			safe = SkillIndex.require(path);
		}
		catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		String branch = branch(accessToken, project);
		try {
			String markdown = repository.fileAt(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), safe, branch);
			return new SkillFile(safe, markdown);
		}
		catch (GitlabIssueException exception) {
			throw failure(exception);
		}
	}

	private String branch(String accessToken, ProjectEntity project) {
		try {
			return repository.defaultBranch(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo());
		}
		catch (GitlabIssueException exception) {
			throw failure(exception);
		}
	}

	private static ResponseStatusException failure(GitlabIssueException exception) {
		if (exception.status() == 401 || exception.status() == 403) {
			return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (exception.status() == 404) {
			return new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

	public record SkillFile(String path, String markdown) {
	}

}
