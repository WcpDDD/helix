package dev.helix.api;

import java.util.List;
import java.util.Map;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.AgentService;
import dev.helix.board.AgentService.AgentDraft;
import dev.helix.board.AgentService.AgentView;
import dev.helix.board.CurrentProject;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

	private final AgentService agents;

	private final CurrentProject currentProject;

	public AgentController(AgentService agents, CurrentProject currentProject) {
		this.agents = agents;
		this.currentProject = currentProject;
	}

	@GetMapping
	public List<AgentView> list(HttpSession session) {
		requireUser(session);
		return agents.list(currentProject.resolve(session));
	}

	@PostMapping
	public AgentView create(@RequestBody AgentDraft draft, HttpSession session) {
		requireUser(session);
		return agents.create(currentProject.resolve(session), draft);
	}

	@PutMapping("/{id}")
	public AgentView update(@PathVariable long id, @RequestBody AgentDraft draft, HttpSession session) {
		requireUser(session);
		return agents.update(currentProject.resolve(session), id, draft);
	}

	@DeleteMapping("/{id}")
	public void delete(@PathVariable long id, HttpSession session) {
		requireUser(session);
		agents.delete(currentProject.resolve(session), id);
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<Map<String, String>> failed(ResponseStatusException exception) {
		String message = exception.getReason();
		if (message == null || message.isBlank()) {
			message = exception.getStatusCode().value() == HttpStatus.CONFLICT.value() ? "已经有同名 agent。" : "请求没有通过。";
		}
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
	}

	private static GitlabUser requireUser(HttpSession session) {
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser user)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return user;
	}

}
