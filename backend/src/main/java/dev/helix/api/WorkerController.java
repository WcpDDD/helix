package dev.helix.api;

import java.util.List;

import dev.helix.worker.SubtaskScheduler;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkerController {

	private final WorkerRegistry registry;

	private final SubtaskScheduler scheduler;

	public WorkerController(WorkerRegistry registry, SubtaskScheduler scheduler) {
		this.registry = registry;
		this.scheduler = scheduler;
	}

	@GetMapping("/workers")
	public List<WorkerView> list() {
		return registry.list();
	}

	@PostMapping("/workers/heartbeat")
	public WorkerView heartbeat(@RequestBody HeartbeatRequest request) {
		String id = request == null ? null : request.id();
		String name = request == null ? null : request.name();
		return registry.heartbeat(id, name);
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

	public record HeartbeatRequest(String id, String name) {
	}

	public record BindingRequest(Long issueId, String workspace) {
	}

	public record ResultRequest(String phase, String text, String failure) {
	}

	public record ScheduleRequest(Long agentId, String message) {
	}

}
