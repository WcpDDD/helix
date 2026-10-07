package dev.helix.gitlab;

public class GitlabIssueException extends RuntimeException {

	private final int status;

	public GitlabIssueException(int status) {
		super("GitLab 返回 " + status);
		this.status = status;
	}

	public int status() {
		return status;
	}

}
