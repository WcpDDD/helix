package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabSessionToken;
import dev.helix.board.CurrentProject;
import dev.helix.board.SpecCheckout;
import dev.helix.board.TaskBoardService;
import dev.helix.board.TaskCreateService;
import dev.helix.board.TaskStatusService;
import dev.helix.board.TaskView;
import dev.helix.board.WorkspaceBoard;
import dev.helix.board.WorkspaceSpecService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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

	private final TaskCreateService taskCreateService;

	private final GitlabSessionToken tokens;

	public TaskController(TaskBoardService taskBoardService, TaskStatusService taskStatusService,
			WorkspaceSpecService specs, WorkspaceBoard boards, CurrentProject currentProject,
			TaskCreateService taskCreateService, GitlabSessionToken tokens) {
		this.taskBoardService = taskBoardService;
		this.taskStatusService = taskStatusService;
		this.specs = specs;
		this.boards = boards;
		this.currentProject = currentProject;
		this.taskCreateService = taskCreateService;
		this.tokens = tokens;
	}

	@PostMapping("/tasks")
	public ResponseEntity<CreatedTask> create(@RequestBody CreateTask request, HttpSession session) {
		var created = taskCreateService.create(tokens.require(session), currentProject.resolve(session), request.title(),
				request.kind(), request.fields(), request.attachments(), Boolean.TRUE.equals(request.delegate()));
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new CreatedTask(Integer.toString(created.iid()), created.webUrl()));
	}

	@PostMapping(value = "/tasks/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public UploadedTaskFile upload(@RequestParam("file") MultipartFile file, HttpSession session) {
		byte[] content = bytes(file);
		var uploaded = taskCreateService.upload(tokens.require(session), currentProject.resolve(session),
				file.getOriginalFilename(), content);
		return new UploadedTaskFile(uploaded.name(), uploaded.markdown());
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
		return specs.checkout(currentProject.resolve(session).getId(), code);
	}

	@PostMapping("/tasks/{code}/status")
	public ResponseEntity<StatusResponse> transition(@PathVariable int code, @RequestBody StatusRequest request,
			HttpSession session) {
		TaskStatusService.Transition result = taskStatusService.transition(tokens.require(session),
				currentProject.resolve(session).getId(), code, request.status());
		StatusResponse body = new StatusResponse(result.status(), result.stage());
		HttpStatus http = result.synced() ? HttpStatus.OK : HttpStatus.CONFLICT;
		return ResponseEntity.status(http).body(body);
	}

	private static byte[] bytes(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件是空的。");
		}
		try {
			return file.getBytes();
		}
		catch (java.io.IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件读不出来。");
		}
	}

	public record CreateTask(String title, String kind, java.util.Map<String, String> fields,
			java.util.List<String> attachments, Boolean delegate) {
	}

	public record UploadedTaskFile(String name, String markdown) {
	}

	public record CreatedTask(String code, String url) {
	}

	public record StatusRequest(String status) {
	}

	public record StatusResponse(String status, String stage) {
	}

}
