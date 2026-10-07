package dev.helix.board;

import java.util.List;

import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.IssueBodyText;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IssueBodyService {

	private final IssueRepository issues;

	private final GitlabIssueClient gitlab;

	public IssueBodyService(IssueRepository issues, GitlabIssueClient gitlab) {
		this.issues = issues;
		this.gitlab = gitlab;
	}

	@Transactional(readOnly = true)
	public String body(long projectId, int number, String accessToken) {
		ProjectEntity project = project(projectId, number);
		String description = read(() -> gitlab.description(accessToken, project.getGitHost(), project.getGitOwner(),
				project.getGitRepo(), number));
		return IssueBodyText.prose(description);
	}

	@Transactional(readOnly = true)
	public List<GitlabComment> comments(long projectId, int number, String accessToken) {
		ProjectEntity project = project(projectId, number);
		return read(() -> gitlab.comments(accessToken, project.getGitHost(), project.getGitOwner(),
				project.getGitRepo(), number));
	}

	private ProjectEntity project(long projectId, int number) {
		return issues.findInProject(projectId, number)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
				.getProject();
	}

	private <T> T read(Reader<T> reader) {
		try {
			return reader.get();
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
	}

	@FunctionalInterface
	private interface Reader<T> {

		T get();

	}

}
