package dev.helix.api;

import java.util.List;

import dev.helix.agentkit.HomeAgentService;
import dev.helix.agentkit.HomeAgentService.HomeFile;
import dev.helix.agentkit.HomeAgentService.HomeView;
import dev.helix.agentkit.HomeObjectStore;
import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.CurrentProject;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/home/agent")
public class HomeAgentController {

	private final HomeAgentService home;

	private final HomeObjectStore objects;

	private final CurrentProject currentProject;

	public HomeAgentController(HomeAgentService home, HomeObjectStore objects, CurrentProject currentProject) {
		this.home = home;
		this.objects = objects;
		this.currentProject = currentProject;
	}

	@GetMapping
	public HomeView read(@RequestParam(name = "session", required = false) String session, HttpServletRequest http) {
		GitlabUser user = requireUser(http);
		var project = currentProject.resolve(http.getSession(false));
		if (session != null && !session.isBlank()) {
			return home.open(project, user, session);
		}
		return home.read(project, user);
	}

	@GetMapping(value = "/stream", produces = "text/event-stream")
	public void stream(@RequestParam(name = "session", required = false) String session, HttpServletRequest http,
			HttpServletResponse response) throws java.io.IOException {
		GitlabUser user = requireUser(http);
		response.setCharacterEncoding("UTF-8");
		response.setContentType("text/event-stream");
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
		response.setBufferSize(1);
		home.stream(currentProject.resolve(http.getSession(false)), user, session, flushing(response));
	}

	@PostMapping("/session")
	public HomeView reset(HttpServletRequest http) {
		GitlabUser user = requireUser(http);
		return home.reset(currentProject.resolve(http.getSession(false)), user);
	}

	@PostMapping
	public HomeView say(@RequestBody SayRequest request, HttpServletRequest http) {
		GitlabUser user = requireUser(http);
		String text = request == null ? "" : request.text();
		List<HomeFile> files = request == null || request.files() == null ? List.of() : request.files();
		return home.say(currentProject.resolve(http.getSession(false)), user, text, files);
	}

	@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public HomeFile upload(@RequestParam("file") MultipartFile file, HttpServletRequest http) {
		requireUser(http);
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件是空的。");
		}
		try {
			return objects.upload(file.getOriginalFilename(), file.getBytes());
		}
		catch (java.io.IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件读不出来。");
		}
	}

	public record SayRequest(String text, List<HomeFile> files) {
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<Map<String, String>> failed(ResponseStatusException exception) {
		String message = exception.getReason();
		if (message == null || message.isBlank()) {
			message = "请求没有通过。";
		}
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of("detail", message));
	}

	private static java.io.OutputStream flushing(HttpServletResponse response) throws java.io.IOException {
		java.io.OutputStream raw = response.getOutputStream();
		return new java.io.OutputStream() {
			@Override
			public void write(int value) throws java.io.IOException {
				raw.write(value);
			}

			@Override
			public void write(byte[] buffer, int offset, int length) throws java.io.IOException {
				raw.write(buffer, offset, length);
			}

			@Override
			public void flush() throws java.io.IOException {
				raw.flush();
				response.flushBuffer();
			}
		};
	}

	private static GitlabUser requireUser(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser user)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return user;
	}

}
