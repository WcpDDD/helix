package dev.helix.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import dev.helix.board.WorkspaceBoard;
import dev.helix.config.HelixProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	static final String SESSION_STATE = "helix.oauth.state";

	public static final String SESSION_USER = "helix.user";

	public static final String SESSION_TOKEN = "helix.gitlab.accessToken";

	public static final String SESSION_REFRESH = "helix.gitlab.refreshToken";

	public static final String SESSION_EXPIRES = "helix.gitlab.expiresAt";

	public static final String SESSION_LOGIN = "helix.login";

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final HelixProperties properties;

	private final GitlabOAuthClient gitlab;

	private final GitlabSessionCredential credential;

	private final LoginStore logins;

	private final WorkspaceBoard boards;

	private final SecureRandom random = new SecureRandom();

	public AuthController(HelixProperties properties, GitlabOAuthClient gitlab, GitlabSessionCredential credential,
			LoginStore logins, WorkspaceBoard boards) {
		this.properties = properties;
		this.gitlab = gitlab;
		this.credential = credential;
		this.logins = logins;
		this.boards = boards;
	}

	@GetMapping("/gitlab")
	public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!properties.gitlab().configured()) {
			response.sendRedirect(frontend("/login?auth_error=not_configured"));
			return;
		}
		String state = newState();
		request.getSession(true).setAttribute(SESSION_STATE, state);
		response.sendRedirect(gitlab.authorizeUri(state).toString());
	}

	@GetMapping({ "/gitlab/callback", "/callback" })
	public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
			@RequestParam(required = false) String error, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		HttpSession session = request.getSession(false);
		String expected = session == null ? null : (String) session.getAttribute(SESSION_STATE);
		if (session != null) {
			session.removeAttribute(SESSION_STATE);
		}
		if (error != null || code == null || code.isBlank() || !same(expected, state)) {
			response.sendRedirect(frontend("/login?auth_error=denied"));
			return;
		}
		try {
			GitlabLogin login = gitlab.exchange(code);
			String loginId = logins.save(login);
			HttpSession signedIn = request.getSession(true);
			signedIn.setAttribute(SESSION_LOGIN, loginId);
			signedIn.setAttribute(SESSION_USER, login.user());
			signedIn.setAttribute(SESSION_TOKEN, login.accessToken());
			if (login.refreshToken() != null && !login.refreshToken().isBlank()) {
				signedIn.setAttribute(SESSION_REFRESH, login.refreshToken());
			}
			if (login.expiresAt() > 0) {
				signedIn.setAttribute(SESSION_EXPIRES, login.expiresAt());
			}
			credential.remember(login.accessToken());
			LoginCookie.write(response, loginId);
			try {
				boards.refresh(login.accessToken());
			}
			catch (RuntimeException exception) {
				log.warn("登录后没有读到 board.yaml: {}", exception.getMessage());
			}
			request.changeSessionId();
			response.sendRedirect(frontend("/"));
		}
		catch (GitlabOAuthException exception) {
			log.warn("GitLab 登录没有完成: {}", exception.getMessage());
			response.sendRedirect(frontend("/login?auth_error=exchange"));
		}
	}

	@GetMapping("/me")
	public GitlabUser me(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(SESSION_USER) instanceof GitlabUser user)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return user;
	}

	@PostMapping("/logout")
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		String loginId = session == null ? null : text(session.getAttribute(SESSION_LOGIN));
		if (loginId == null) {
			loginId = LoginCookie.read(request);
		}
		if (loginId != null) {
			logins.delete(loginId);
		}
		LoginCookie.clear(response);
		if (session != null) {
			Object token = session.getAttribute(SESSION_TOKEN);
			session.invalidate();
			if (token instanceof String accessToken) {
				credential.forget(accessToken);
			}
		}
		response.setStatus(HttpStatus.NO_CONTENT.value());
	}

	private static String text(Object value) {
		if (!(value instanceof String text) || text.isBlank()) {
			return null;
		}
		return text;
	}

	private String frontend(String pathAndQuery) {
		String base = properties.frontendUrl() == null ? "" : properties.frontendUrl().trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + pathAndQuery;
	}

	private String newState() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static boolean same(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

}
