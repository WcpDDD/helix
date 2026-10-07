package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.CurrentProject;
import dev.helix.board.MemberRoleService;
import dev.helix.board.MemberRoleService.MemberDirectory;
import dev.helix.board.MemberRoleService.MemberRoleView;
import dev.helix.board.MemberSyncService;
import dev.helix.board.MemberSyncService.MemberBoard;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/members")
public class MemberController {

	private final MemberRoleService roles;

	private final MemberSyncService sync;

	private final CurrentProject currentProject;

	public MemberController(MemberRoleService roles, MemberSyncService sync, CurrentProject currentProject) {
		this.roles = roles;
		this.sync = sync;
		this.currentProject = currentProject;
	}

	@GetMapping
	public MemberBoard list(HttpServletRequest http) {
		return sync.board(currentProject.resolve(http.getSession(false)));
	}

	@PostMapping("/sync")
	public MemberBoard sync(HttpServletRequest http) {
		return sync.sync(token(http), currentProject.resolve(http.getSession(false)));
	}

	@GetMapping("/roles")
	public MemberDirectory roles(HttpServletRequest http) {
		return roles.list(currentProject.resolve(http.getSession(false)));
	}

	@PostMapping("/labels")
	public List<String> create(@RequestBody LabelRequest request, HttpServletRequest http) {
		requireUser(http);
		return roles.create(currentProject.resolve(http.getSession(false)), request == null ? null : request.name());
	}

	@PutMapping("/{login}/roles")
	public MemberRoleView assign(@PathVariable String login, @RequestBody RoleRequest request, HttpServletRequest http) {
		requireUser(http);
		String name = request == null ? null : request.name();
		List<String> next = request == null ? null : request.roles();
		return roles.assign(currentProject.resolve(http.getSession(false)), login, name, next);
	}

	private static String token(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		Object token = session == null ? null : session.getAttribute(AuthController.SESSION_TOKEN);
		if (!(token instanceof String accessToken) || accessToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return accessToken;
	}

	private static void requireUser(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
	}

	public record RoleRequest(String name, List<String> roles) {
	}

	public record LabelRequest(String name) {
	}

}
