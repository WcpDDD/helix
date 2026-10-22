package dev.helix.board;

import java.util.List;

import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.IssueBodyText;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IssueBodyService {

	private final GitlabIssueClient gitlab;

	public IssueBodyService(GitlabIssueClient gitlab) {
		this.gitlab = gitlab;
	}

	public String body(ProjectEntity project, int number, String accessToken) {
		String description = read(() -> gitlab.description(accessToken, project.getGitHost(), project.getGitOwner(),
				project.getGitRepo(), number));
		return IssueBodyText.prose(description);
	}

	public List<GitlabComment> comments(ProjectEntity project, int number, String accessToken) {
		return read(() -> gitlab.comments(accessToken, project.getGitHost(), project.getGitOwner(),
				project.getGitRepo(), number));
	}

	private <T> T read(Reader<T> reader) {
		try {
			return reader.get();
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			if (exception.status() == 404) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
	}

	@FunctionalInterface
	private interface Reader<T> {

		T get();

	}

}
