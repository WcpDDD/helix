package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabSessionToken;
import dev.helix.auth.GitlabUser;
import dev.helix.board.CurrentProject;
import dev.helix.board.ProjectAccess;
import dev.helix.board.ProjectEntity;
import dev.helix.observe.ObserveService;
import dev.helix.observe.ObserveService.RunCard;
import dev.helix.observe.ObserveService.RunDetail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/observe")
public class ObserveController {

	private final ObserveService observe;

	private final CurrentProject currentProject;

	private final GitlabSessionToken tokens;

	private final ProjectAccess access;

	public ObserveController(ObserveService observe, CurrentProject currentProject, GitlabSessionToken tokens,
			ProjectAccess access) {
		this.observe = observe;
		this.currentProject = currentProject;
		this.tokens = tokens;
		this.access = access;
	}

	@GetMapping("/runs")
	public List<RunCard> runs(HttpSession session) {
		return observe.runs(permit(session));
	}

	@GetMapping("/runs/open")
	public RunDetail open(@RequestParam String place, @RequestParam(required = false) String worker,
			@RequestParam(defaultValue = "0") int issue, @RequestParam(required = false) String agent,
			@RequestParam(required = false) String sessionId, HttpSession session) {
		return observe.open(permit(session), place, worker, issue, agent, sessionId);
	}

	private ProjectEntity permit(HttpSession session) {
		ProjectEntity project = currentProject.resolve(session);
		access.requireMaintainer(tokens.require(session), project, user(session), "只有维护者可以看 Agent 运行情况。");
		return project;
	}

	private static GitlabUser user(HttpSession session) {
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser user)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return user;
	}

}
