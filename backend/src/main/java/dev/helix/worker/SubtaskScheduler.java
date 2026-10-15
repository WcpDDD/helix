package dev.helix.worker;

import java.util.List;

import dev.helix.board.AgentEntity;
import dev.helix.board.AgentRepository;
import dev.helix.board.AgentSkillEntity;
import dev.helix.board.IssueEntity;
import dev.helix.board.IssueRepository;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubtaskScheduler {

	private final IssueRepository issues;

	private final AgentRepository agents;

	private final SubtaskRouteRepository routes;

	private final WorkerRegistry registry;

	public SubtaskScheduler(IssueRepository issues, AgentRepository agents, SubtaskRouteRepository routes,
			WorkerRegistry registry) {
		this.issues = issues;
		this.agents = agents;
		this.routes = routes;
		this.registry = registry;
	}

	@Transactional
	public ScheduleView schedule(long issueId, long agentId, String message) {
		IssueEntity issue = child(issueId);
		AgentEntity agent = agents.findById(agentId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个 agent。"));
		if (!agent.getProject().getId().equals(issue.getProject().getId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent 和子任务不在同一个项目。");
		}
		String prompt = required(message, "要说一句让这个 agent 做的事。");
		return routes.findById(issueId)
				.map(route -> routeExisting(route))
				.orElseGet(() -> dispatch(issue, agent, prompt));
	}

	@Transactional(readOnly = true)
	public AgentSessionView session(long projectId, int number) {
		IssueEntity issue;
		try {
			issue = issues.findInProject(projectId, number).orElse(null);
		}
		catch (DataAccessException exception) {
			return AgentSessionView.none();
		}
		if (issue == null) {
			return AgentSessionView.none();
		}
		SubtaskRouteEntity route = routes.findById(issue.getId()).orElse(null);
		if (route == null) {
			return AgentSessionView.none();
		}
		WorkerRegistry.WorkerView worker = registry.list().stream()
				.filter(item -> item.id().equals(route.getWorkerId()))
				.findFirst()
				.orElse(null);
		boolean alive = worker != null && worker.alive();
		String name = worker == null ? "" : worker.name();
		return new AgentSessionView(true, alive, route.getWorkerId(), name, route.getMessage(), route.getPhase(),
				route.getResultText(), route.getFailure());
	}

	@Transactional(readOnly = true)
	public List<Dispatch> dispatches(String workerId) {
		return routes.findByWorkerIdAndRouteStateAndWorkspaceIsNull(workerId, "pending").stream()
				.map(Dispatch::of)
				.toList();
	}

	@Transactional
	public ScheduleView bind(String workerId, long issueId, String workspace) {
		SubtaskRouteEntity route = owned(workerId, issueId);
		if ("interrupted".equals(route.getRouteState())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "这条子任务的调度已中断，等待修复。");
		}
		String path = required(workspace, "workspace 路径不能空。");
		if (route.getWorkspace() != null && !route.getWorkspace().equals(path)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "这条子任务已经有 workspace。");
		}
		route.bind(path);
		return ScheduleView.of(route);
	}

	@Transactional
	public ScheduleView finish(String workerId, long issueId, String phase, String text, String failure) {
		SubtaskRouteEntity route = owned(workerId, issueId);
		if (route.getWorkspace() == null || route.getWorkspace().isBlank()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "还没记下 workspace，不能回写结果。");
		}
		route.finish(phase, text, failure);
		return ScheduleView.of(route);
	}

	private ScheduleView routeExisting(SubtaskRouteEntity route) {
		if (!registry.alive(route.getWorkerId())) {
			route.interrupt();
			return ScheduleView.of(route);
		}
		route.resume();
		return ScheduleView.of(route);
	}

	private ScheduleView dispatch(IssueEntity issue, AgentEntity agent, String message) {
		WorkerEntity worker = registry.requireLive();
		SubtaskRouteEntity route = routes.save(new SubtaskRouteEntity(
				issue.getId(), worker.getId(), agent.getId(), prompt(agent), message));
		return ScheduleView.of(route);
	}

	private SubtaskRouteEntity owned(String workerId, long issueId) {
		SubtaskRouteEntity route = routes.findById(issueId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这条子任务还没分配。"));
		if (!route.getWorkerId().equals(workerId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "这条子任务不在这个 worker 上。");
		}
		return route;
	}

	private IssueEntity child(long issueId) {
		IssueEntity issue = issues.findById(issueId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个子任务。"));
		if (issue.isMainIssue()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "worker 绑在子任务上。");
		}
		return issue;
	}

	private static String prompt(AgentEntity agent) {
		if (agent.getSkills().isEmpty()) {
			return agent.getBody();
		}
		StringBuilder text = new StringBuilder(agent.getBody()).append("\n\n引用的 skill：");
		for (AgentSkillEntity skill : agent.getSkills()) {
			text.append('\n').append(skill.getSkillPath());
		}
		return text.toString();
	}

	private static String required(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
		}
		return value.strip();
	}

	public record Dispatch(long issueId, long agentId, String systemPrompt, String message) {
		static Dispatch of(SubtaskRouteEntity route) {
			return new Dispatch(route.getIssueId(), route.getAgentId(), route.getSystemPrompt(), route.getMessage());
		}
	}

	public record AgentSessionView(boolean assigned, boolean alive, String workerId, String workerName, String message,
			String phase, String text, String failure) {

		static AgentSessionView none() {
			return new AgentSessionView(false, false, "", "", "", "", "", "");
		}
	}

	public record ScheduleView(long issueId, String state, String workerId, String workspace, String phase, String text,
			String failure) {
		static ScheduleView of(SubtaskRouteEntity route) {
			return new ScheduleView(route.getIssueId(), route.getRouteState(), route.getWorkerId(), route.getWorkspace(),
					route.getPhase(), route.getResultText(), route.getFailure());
		}
	}

}
