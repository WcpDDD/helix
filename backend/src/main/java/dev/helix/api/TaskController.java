package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabSessionToken;
import dev.helix.board.CurrentProject;
import dev.helix.board.SpecCheckout;
import dev.helix.board.TaskBoardService;
import dev.helix.board.TaskStatusService;
import dev.helix.board.TaskView;
import dev.helix.board.WorkspaceBoard;
import dev.helix.board.WorkspaceSpecService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api")
public class TaskController {

	private final TaskBoardService taskBoardService;

	private final TaskStatusService taskStatusService;

	private final WorkspaceSpecService specs;

	private final WorkspaceBoard boards;

	private final CurrentProject currentProject;

	private final GitlabSessionToken tokens;

	public TaskController(TaskBoardService taskBoardService, TaskStatusService taskStatusService,
			WorkspaceSpecService specs, WorkspaceBoard boards, CurrentProject currentProject,
			GitlabSessionToken tokens) {
		this.taskBoardService = taskBoardService;
		this.taskStatusService = taskStatusService;
		this.specs = specs;
		this.boards = boards;
		this.currentProject = currentProject;
		this.tokens = tokens;
	}

	@GetMapping("/tasks")
	public List<TaskView> list(HttpSession session) {
		var project = currentProject.resolve(session);
		if (boards.pinnedClasspath()) {
			return taskBoardService.list(project.getId());
		}
		String accessToken = tokens.require(session);
		try {
			return taskBoardService.listCheckout(project, boards.structure(project, accessToken), accessToken);
		}
		catch (ResponseStatusException exception) {
			if (exception.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
				throw exception;
			}
			String refreshed = tokens.refresh(session);
			return taskBoardService.listCheckout(project, boards.structure(project, refreshed), refreshed);
		}
	}

	@GetMapping("/tasks/{code}/spec")
	public SpecCheckout spec(@PathVariable int code, HttpSession session) {
		return specs.checkout(token(session), currentProject.resolve(session).getId(), code);
	}

	@PostMapping("/tasks/{code}/status")
	public ResponseEntity<StatusResponse> transition(@PathVariable int code, @RequestBody StatusRequest request,
			HttpSession session) {
		TaskStatusService.Transition result = taskStatusService.transition(token(session),
				currentProject.resolve(session).getId(), code, request.status());
		StatusResponse body = new StatusResponse(result.status(), result.stage());
		HttpStatus http = result.synced() ? HttpStatus.OK : HttpStatus.CONFLICT;
		return ResponseEntity.status(http).body(body);
	}

	private static String token(HttpSession session) {
		String accessToken = tokenOrNull(session);
		if (accessToken == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return accessToken;
	}

	private static String tokenOrNull(HttpSession session) {
		Object token = session == null ? null : session.getAttribute(AuthController.SESSION_TOKEN);
		if (!(token instanceof String accessToken) || accessToken.isBlank()) {
			return null;
		}
		return accessToken;
	}

	public record StatusRequest(String status) {
	}

	public record StatusResponse(String status, String stage) {
	}

}
