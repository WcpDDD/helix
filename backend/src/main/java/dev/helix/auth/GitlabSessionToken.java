package dev.helix.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@Service
public class GitlabSessionToken {

	private static final long SKEW = 30_000;

	private final GitlabOAuthClient oauth;

	private final GitlabSessionCredential credential;

	public GitlabSessionToken(GitlabOAuthClient oauth, GitlabSessionCredential credential) {
		this.oauth = oauth;
		this.credential = credential;
	}

	public String require(HttpSession session) {
		String accessToken = text(session, AuthController.SESSION_TOKEN);
		if (accessToken == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		long expiresAt = number(session);
		if (expiresAt > 0 && System.currentTimeMillis() + SKEW >= expiresAt) {
			return refresh(session);
		}
		return accessToken;
	}

	public String refresh(HttpSession session) {
		String refreshToken = text(session, AuthController.SESSION_REFRESH);
		if (refreshToken == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		GitlabLogin login;
		try {
			login = oauth.refresh(refreshToken);
		}
		catch (GitlabOAuthException exception) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		session.setAttribute(AuthController.SESSION_TOKEN, login.accessToken());
		session.setAttribute(AuthController.SESSION_REFRESH, login.refreshToken());
		if (login.expiresAt() > 0) {
			session.setAttribute(AuthController.SESSION_EXPIRES, login.expiresAt());
		}
		credential.remember(login.accessToken());
		return login.accessToken();
	}

	private static String text(HttpSession session, String key) {
		if (session == null) {
			return null;
		}
		Object value = session.getAttribute(key);
		if (!(value instanceof String text) || text.isBlank()) {
			return null;
		}
		return text;
	}

	private static long number(HttpSession session) {
		if (session == null) {
			return 0;
		}
		Object value = session.getAttribute(AuthController.SESSION_EXPIRES);
		if (value instanceof Long expiresAt) {
			return expiresAt;
		}
		if (value instanceof Integer expiresAt) {
			return expiresAt.longValue();
		}
		return 0;
	}

}
