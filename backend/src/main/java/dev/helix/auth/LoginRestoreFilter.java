package dev.helix.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LoginRestoreFilter extends OncePerRequestFilter {

	private static final long SKEW = 30_000;

	private static final Logger log = LoggerFactory.getLogger(LoginRestoreFilter.class);

	private final LoginStore logins;

	private final GitlabSessionToken tokens;

	private final GitlabSessionCredential credential;

	public LoginRestoreFilter(LoginStore logins, GitlabSessionToken tokens, GitlabSessionCredential credential) {
		this.logins = logins;
		this.tokens = tokens;
		this.credential = credential;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, java.io.IOException {
		HttpSession existing = request.getSession(false);
		if (existing == null || !(existing.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser)) {
			restore(request, response);
		}
		chain.doFilter(request, response);
	}

	private void restore(HttpServletRequest request, HttpServletResponse response) {
		String id = LoginCookie.read(request);
		if (id == null) {
			return;
		}
		GitlabLoginEntity row = logins.find(id).orElse(null);
		if (row == null) {
			return;
		}
		HttpSession session = request.getSession(true);
		session.setAttribute(AuthController.SESSION_LOGIN, row.getId());
		session.setAttribute(AuthController.SESSION_USER, row.user());
		session.setAttribute(AuthController.SESSION_TOKEN, row.getAccessToken());
		if (row.getRefreshToken() != null && !row.getRefreshToken().isBlank()) {
			session.setAttribute(AuthController.SESSION_REFRESH, row.getRefreshToken());
		}
		if (row.getExpiresAt() > 0) {
			session.setAttribute(AuthController.SESSION_EXPIRES, row.getExpiresAt());
		}
		credential.remember(row.getAccessToken());
		if (row.getExpiresAt() > 0 && System.currentTimeMillis() + SKEW >= row.getExpiresAt()) {
			try {
				tokens.refresh(session);
			}
			catch (ResponseStatusException exception) {
				log.warn("保存的 GitLab 登录没有续上");
				forget(session, response, id);
			}
		}
	}

	private void forget(HttpSession session, HttpServletResponse response, String id) {
		session.removeAttribute(AuthController.SESSION_LOGIN);
		session.removeAttribute(AuthController.SESSION_USER);
		session.removeAttribute(AuthController.SESSION_TOKEN);
		session.removeAttribute(AuthController.SESSION_REFRESH);
		session.removeAttribute(AuthController.SESSION_EXPIRES);
		logins.delete(id);
		LoginCookie.clear(response);
	}

}
