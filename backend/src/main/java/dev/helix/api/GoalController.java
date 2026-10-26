package dev.helix.api;

import dev.helix.auth.AuthController;
import dev.helix.board.CurrentProject;
import dev.helix.board.GoalService;
import dev.helix.board.GoalService.GoalView;
import dev.helix.board.ProjectEntity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/issues")
public class GoalController {

	private final GoalService goals;

	private final CurrentProject currentProject;

	public GoalController(GoalService goals, CurrentProject currentProject) {
		this.goals = goals;
		this.currentProject = currentProject;
	}

	@GetMapping("/{number}/goal")
	public GoalView status(@PathVariable int number, HttpSession session) {
		ProjectEntity project = currentProject.resolve(session);
		return goals.status(token(session), project, number);
	}

	@PostMapping("/{number}/goal")
	public GoalView start(@PathVariable int number, @RequestBody GoalRequest request, HttpSession session) {
		ProjectEntity project = currentProject.resolve(session);
		String text = request == null ? "" : request.text();
		return goals.start(token(session), project, number, text);
	}

	@DeleteMapping("/{number}/goal")
	public void stop(@PathVariable int number, HttpSession session) {
		ProjectEntity project = currentProject.resolve(session);
		goals.stop(token(session), project, number);
	}

	private static String token(HttpSession session) {
		Object token = session == null ? null : session.getAttribute(AuthController.SESSION_TOKEN);
		if (!(token instanceof String accessToken) || accessToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return accessToken;
	}

	public record GoalRequest(String text) {
	}

}
