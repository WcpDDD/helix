package dev.helix.auth;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@Service
public class GitlabSessionToken {

	private static final long SKEW = 30_000;

	private static final Logger log = LoggerFactory.getLogger(GitlabSessionToken.class);

	private final GitlabOAuthClient oauth;

	private final GitlabSessionCredential credential;

	private final LoginStore logins;

	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	public GitlabSessionToken(GitlabOAuthClient oauth, GitlabSessionCredential credential, LoginStore logins) {
		this.oauth = oauth;
		this.credential = credential;
		this.logins = logins;
	}

	public String require(HttpSession session) {
		String adopted = adoptStored(session);
		if (adopted != null) {
			return adopted;
		}
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
		String loginId = text(session, AuthController.SESSION_LOGIN);
		Object lock = loginId == null ? session : locks.computeIfAbsent(loginId, id -> new Object());
		synchronized (lock) {
			String adopted = adoptStored(session);
			if (adopted != null) {
				return adopted;
			}
			GitlabOAuthException failure = null;
			String sessionRefresh = text(session, AuthController.SESSION_REFRESH);
			if (sessionRefresh != null) {
				try {
					return apply(session, loginId, oauth.refresh(sessionRefresh));
				}
				catch (GitlabOAuthException exception) {
					failure = exception;
				}
			}
			String storedRefresh = storedRefresh(loginId);
			if (storedRefresh != null && !storedRefresh.equals(sessionRefresh)) {
				try {
					return apply(session, loginId, oauth.refresh(storedRefresh));
				}
				catch (GitlabOAuthException exception) {
					failure = exception;
				}
			}
			if (failure != null) {
				log.warn("GitLab 登录没有续上: {}", failure.getMessage());
			}
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
	}

	private String adoptStored(HttpSession session) {
		String loginId = text(session, AuthController.SESSION_LOGIN);
		if (loginId == null) {
			return null;
		}
		GitlabLoginEntity row = logins.find(loginId).orElse(null);
		if (row == null || row.getAccessToken() == null || row.getAccessToken().isBlank()) {
			return null;
		}
		if (row.getExpiresAt() > 0 && System.currentTimeMillis() + SKEW >= row.getExpiresAt()) {
			return null;
		}
		if (row.getAccessToken().equals(text(session, AuthController.SESSION_TOKEN))) {
			return null;
		}
		session.setAttribute(AuthController.SESSION_TOKEN, row.getAccessToken());
		if (row.getRefreshToken() != null && !row.getRefreshToken().isBlank()) {
			session.setAttribute(AuthController.SESSION_REFRESH, row.getRefreshToken());
		}
		if (row.getExpiresAt() > 0) {
			session.setAttribute(AuthController.SESSION_EXPIRES, row.getExpiresAt());
		}
		credential.remember(row.getAccessToken());
		log.info("会话里的 GitLab 令牌已换过，改用保存的这一份");
		return row.getAccessToken();
	}

	private String apply(HttpSession session, String loginId, GitlabLogin login) {
		session.setAttribute(AuthController.SESSION_TOKEN, login.accessToken());
		session.setAttribute(AuthController.SESSION_REFRESH, login.refreshToken());
		if (login.expiresAt() > 0) {
			session.setAttribute(AuthController.SESSION_EXPIRES, login.expiresAt());
		}
		if (loginId != null) {
			logins.updateTokens(loginId, login.accessToken(), login.refreshToken(), login.expiresAt());
		}
		credential.remember(login.accessToken());
		return login.accessToken();
	}

	private String storedRefresh(String loginId) {
		if (loginId == null) {
			return null;
		}
		GitlabLoginEntity row = logins.find(loginId).orElse(null);
		if (row == null || row.getRefreshToken() == null || row.getRefreshToken().isBlank()) {
			return null;
		}
		return row.getRefreshToken();
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
