package dev.helix.board;

import dev.helix.auth.GitlabUser;
import dev.helix.gitlab.GitlabDirectoryClient;
import dev.helix.gitlab.GitlabIssueException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectAccess {

	private final AgentAudience audience;

	private final GitlabDirectoryClient gitlab;

	public ProjectAccess(AgentAudience audience, GitlabDirectoryClient gitlab) {
		this.audience = audience;
		this.gitlab = gitlab;
	}

	public String of(String accessToken, ProjectEntity project, GitlabUser user) {
		String stored = audience.accessOf(project.getId(), user == null ? "" : user.username());
		if (!stored.isBlank()) {
			return stored;
		}
		if (user == null || user.id() <= 0) {
			return "";
		}
		try {
			return AgentAccess.fromLevel(gitlab.accessLevel(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), user.id()));
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			if (exception.status() == 403) {
				return "";
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
	}

	public void requireMaintainer(String accessToken, ProjectEntity project, GitlabUser user) {
		if (!"maintainer".equals(of(accessToken, project, user))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有维护者可以改预计上线时间。");
		}
	}

}
