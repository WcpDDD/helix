package dev.helix.api;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.helix.board.CurrentProject;
import dev.helix.worker.AgentSessionService;
import dev.helix.worker.SubtaskScheduler;
import dev.helix.worker.SubtaskScheduler.AgentSessionView;
import dev.helix.worker.SubtaskScheduler.Dispatch;
import dev.helix.worker.SubtaskScheduler.ScheduleView;
import dev.helix.worker.WorkerRegistry;
import dev.helix.worker.WorkerRegistry.WorkerView;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api")
public class WorkerController {

	private static final JsonMapper JSON = new JsonMapper();

	private final WorkerRegistry registry;

	private final SubtaskScheduler scheduler;

	private final AgentSessionService agentSessions;

	private final CurrentProject currentProject;

	public WorkerController(WorkerRegistry registry, SubtaskScheduler scheduler, AgentSessionService agentSessions,
			CurrentProject currentProject) {
		this.registry = registry;
		this.scheduler = scheduler;
		this.agentSessions = agentSessions;
		this.currentProject = currentProject;
	}

	@GetMapping("/issues/{number}/agent-session")
	public AgentSessionView session(@PathVariable int number, HttpSession session) {
		return agentSessions.open(currentProject.resolve(session).getId(), number);
	}

	@PostMapping("/issues/{number}/agent-session")
	public ResponseEntity<?> create(@PathVariable int number, HttpSession session) {
		try {
			return ResponseEntity.ok(agentSessions.create(currentProject.resolve(session).getId(), number));
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/issues/{number}/agent-sessions")
	public List<AgentSessionService.IssueAgentView> roster(@PathVariable int number, HttpSession session) {
		return agentSessions.roster(currentProject.resolve(session).getId(), number);
	}

	@PostMapping("/issues/{number}/agents/{agentId}/session")
	public ResponseEntity<?> createAgent(@PathVariable int number, @PathVariable long agentId, HttpSession session) {
		try {
			return ResponseEntity.ok(agentSessions.create(currentProject.resolve(session).getId(), number, agentId));
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/issues/{number}/agents/{agentId}/conversations")
	public ResponseEntity<?> agentConversations(@PathVariable int number, @PathVariable long agentId,
			@RequestParam(required = false) Long before, HttpSession session) {
		try {
			String body = agentSessions.conversations(currentProject.resolve(session).getId(), number, agentId, before);
			return json(body);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/issues/{number}/agents/{agentId}/conversations/{seq}")
	public ResponseEntity<?> agentConversation(@PathVariable int number, @PathVariable long agentId, @PathVariable long seq,
			HttpSession session) {
		try {
			String body = agentSessions.conversation(currentProject.resolve(session).getId(), number, agentId, seq);
			return json(body);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@PostMapping("/issues/{number}/agents/{agentId}/messages")
	public ResponseEntity<?> agentSay(@PathVariable int number, @PathVariable long agentId, @RequestBody String body,
			HttpSession session) {
		try {
			String response = agentSessions.say(currentProject.resolve(session).getId(), number, agentId, body);
			return json(response);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/issues/{number}/agents/{agentId}/files")
	public ResponseEntity<?> agentFile(@PathVariable int number, @PathVariable long agentId,
			@RequestParam(required = false) String path, HttpSession session) {
		try {
			String body = agentSessions.file(currentProject.resolve(session).getId(), number, agentId, path);
			return json(body);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping(value = "/issues/{number}/agents/{agentId}/conversations/{seq}/stream", produces = "text/event-stream")
	public void agentStream(@PathVariable int number, @PathVariable long agentId, @PathVariable long seq, HttpSession session,
			jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
		response.setCharacterEncoding("UTF-8");
		response.setContentType("text/event-stream");
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
		response.setBufferSize(1);
		try {
			agentSessions.stream(currentProject.resolve(session).getId(), number, agentId, seq, flushing(response));
		}
		catch (ResponseStatusException exception) {
			if (response.isCommitted()) {
				return;
			}
			response.setStatus(exception.getStatusCode().value());
			response.setContentType("application/json");
			String detail = exception.getReason() == null ? "对话没有读到。" : exception.getReason();
			response.getWriter().write("{\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
		}
	}

	@GetMapping("/issues/{number}/conversations")
	public ResponseEntity<?> conversations(@PathVariable int number, @RequestParam(required = false) Long before,
			HttpSession session) {
		try {
			String body = agentSessions.conversations(currentProject.resolve(session).getId(), number, before);
			return json(body);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/issues/{number}/conversations/{seq}")
	public ResponseEntity<?> conversation(@PathVariable int number, @PathVariable long seq, HttpSession session) {
		try {
			String body = agentSessions.conversation(currentProject.resolve(session).getId(), number, seq);
			return json(body);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@PostMapping("/issues/{number}/messages")
	public ResponseEntity<?> say(@PathVariable int number, @RequestBody String body, HttpSession session) {
		try {
			String response = agentSessions.say(currentProject.resolve(session).getId(), number, body);
			return json(response);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/issues/{number}/files")
	public ResponseEntity<?> file(@PathVariable int number, @RequestParam(required = false) String path, HttpSession session) {
		try {
			String body = agentSessions.file(currentProject.resolve(session).getId(), number, path);
			return json(body);
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping(value = "/issues/{number}/conversations/{seq}/stream", produces = "text/event-stream")
	public void stream(@PathVariable int number, @PathVariable long seq, HttpSession session,
			jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
		response.setCharacterEncoding("UTF-8");
		response.setContentType("text/event-stream");
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
		response.setBufferSize(1);
		try {
			agentSessions.stream(currentProject.resolve(session).getId(), number, seq, flushing(response));
		}
		catch (ResponseStatusException exception) {
			if (response.isCommitted()) {
				return;
			}
			response.setStatus(exception.getStatusCode().value());
			response.setContentType("application/json");
			String detail = exception.getReason() == null ? "对话没有读到。" : exception.getReason();
			response.getWriter().write("{\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
		}
	}

	@PostMapping("/workers/{id}/issues/{number}/agents/{agent}/tasks")
	public ResponseEntity<?> task(@PathVariable String id, @PathVariable int number, @PathVariable String agent,
			@RequestBody String body) {
		try {
			return json(agentSessions.dispatch(id, number, agent, body));
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/workers/{id}/issues/{number}/agents/{agent}/conversations")
	public ResponseEntity<?> catalog(@PathVariable String id, @PathVariable int number, @PathVariable String agent,
			@RequestParam(required = false) Long before) {
		try {
			return json(agentSessions.catalog(id, number, agent, before));
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/workers/{id}/issues/{number}/agents/{agent}/conversations/{seq}")
	public ResponseEntity<?> transcript(@PathVariable String id, @PathVariable int number, @PathVariable String agent,
			@PathVariable long seq) {
		try {
			return json(agentSessions.transcript(id, number, agent, seq));
		}
		catch (ResponseStatusException exception) {
			return failure(exception);
		}
	}

	@GetMapping("/workers")
	public List<WorkerView> list() {
		return registry.list();
	}

	@PostMapping("/workers/heartbeat")
	public WorkerView heartbeat(@RequestBody HeartbeatRequest request) {
		String id = request == null ? null : request.id();
		String name = request == null ? null : request.name();
		String address = request == null ? null : request.address();
		return registry.heartbeat(id, name, address);
	}

	@GetMapping("/workers/{id}/dispatches")
	public List<Dispatch> dispatches(@PathVariable String id) {
		return scheduler.dispatches(id);
	}

	@PostMapping("/workers/{id}/bindings")
	public ScheduleView bind(@PathVariable String id, @RequestBody BindingRequest request) {
		long issueId = request == null || request.issueId() == null ? 0 : request.issueId();
		String workspace = request == null ? null : request.workspace();
		return scheduler.bind(id, issueId, workspace);
	}

	@PostMapping("/workers/{id}/subtasks/{issueId}/result")
	public ScheduleView finish(@PathVariable String id, @PathVariable long issueId, @RequestBody ResultRequest request) {
		String phase = request == null ? null : request.phase();
		String text = request == null ? null : request.text();
		String failure = request == null ? null : request.failure();
		return scheduler.finish(id, issueId, phase, text, failure);
	}

	@PostMapping("/subtasks/{issueId}/schedule")
	public ResponseEntity<ScheduleView> schedule(@PathVariable long issueId, @RequestBody ScheduleRequest request) {
		long agentId = request == null || request.agentId() == null ? 0 : request.agentId();
		String message = request == null ? null : request.message();
		ScheduleView view = scheduler.schedule(issueId, agentId, message);
		HttpStatus status = "interrupted".equals(view.state()) ? HttpStatus.CONFLICT : HttpStatus.OK;
		return ResponseEntity.status(status).body(view);
	}

	public record HeartbeatRequest(String id, String name, String address) {
	}

	public record BindingRequest(Long issueId, String workspace) {
	}

	public record ResultRequest(String phase, String text, String failure) {
	}

	public record ScheduleRequest(Long agentId, String message) {
	}

	private static java.io.OutputStream flushing(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
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

	private static ResponseEntity<Map<String, String>> failure(ResponseStatusException exception) {
		String detail = exception.getReason() == null ? "对话没有读到。" : exception.getReason();
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of("detail", detail));
	}

	private static ResponseEntity<JsonNode> json(String body) {
		return ResponseEntity.ok(JSON.readTree(body));
	}

}
