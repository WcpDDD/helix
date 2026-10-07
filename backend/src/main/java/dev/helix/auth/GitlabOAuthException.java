package dev.helix.auth;

public class GitlabOAuthException extends RuntimeException {

	public GitlabOAuthException(String message) {
		super(message);
	}

	public GitlabOAuthException(String message, Throwable cause) {
		super(message, cause);
	}

}
