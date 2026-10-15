package dev.helix.worker;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.helix.board.AgentEntity;
import dev.helix.board.AgentRepository;
import dev.helix.worker.SubtaskScheduler.AgentSessionView;
import dev.helix.worker.WorkerRegistry.CallableWorker;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentSessionService {

	private final AgentSessionRepository sessions;

	private final WorkerRegistry registry;

	private final WorkerSessionClient workers;

	private final WorkerConversationClient conversations;

	private final SubtaskScheduler scheduler;

	private final AgentRepository definitions;

	public AgentSessionService(AgentSessionRepository sessions, WorkerRegistry registry, WorkerSessionClient workers,
			WorkerConversationClient conversations, SubtaskScheduler scheduler, AgentRepository definitions) {
		this.sessions = sessions;
		this.registry = registry;
		this.workers = workers;
		this.conversations = conversations;
		this.scheduler = scheduler;
		this.definitions = definitions;
	}

	public AgentSessionView open(long projectId, int number) {
		return sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, 0)
				.map(this::view)
				.orElseGet(() -> scheduler.session(projectId, number));
	}

	public AgentSessionView create(long projectId, int number) {
		if (number <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issue 编号不对。");
		}
		AgentSessionEntity existing = sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, 0).orElse(null);
		if (existing != null) {
			return view(existing);
		}
		Map<String, Long> load = sessions.findAll().stream()
				.collect(Collectors.groupingBy(AgentSessionEntity::getWorkerId, Collectors.counting()));
		CallableWorker worker = registry.lightest(load)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有可用的 Worker。"));
		try {
			workers.create(worker.address(), number, 0);
		}
		catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "创建 Agent 会话失败。");
		}
		try {
			return view(sessions.save(new AgentSessionEntity(projectId, number, 0, worker.id())));
		}
		catch (DataIntegrityViolationException exception) {
			return sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, 0).map(this::view)
					.orElseThrow(() -> exception);
		}
	}

	public List<IssueAgentView> roster(long projectId, int number) {
		return definitions.findByProject_IdOrderByNameAsc(projectId).stream()
				.map(agent -> seat(projectId, number, agent))
				.toList();
	}

	public IssueAgentView create(long projectId, int number, long agentId) {
		if (number <= 0 || agentId <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issue 或 agent 不对。");
		}
		AgentEntity agent = definitions.findByIdAndProject_Id(agentId, projectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个 agent。"));
		AgentSessionEntity existing = sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, agentId).orElse(null);
		if (existing != null) {
			return seat(agent, existing);
		}
		Map<String, Long> load = sessions.findAll().stream()
				.collect(Collectors.groupingBy(AgentSessionEntity::getWorkerId, Collectors.counting()));
		CallableWorker worker = registry.lightest(load)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有可用的 Worker。"));
		try {
			workers.create(worker.address(), number, agentId);
		}
		catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "创建 Agent 会话失败。");
		}
		try {
			return seat(agent, sessions.save(new AgentSessionEntity(projectId, number, agentId, worker.id())));
		}
		catch (DataIntegrityViolationException exception) {
			return sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, agentId)
					.map(saved -> seat(agent, saved))
					.orElseThrow(() -> exception);
		}
	}

	private AgentSessionView view(AgentSessionEntity session) {
		WorkerRegistry.WorkerView worker = registry.list().stream()
				.filter(item -> item.id().equals(session.getWorkerId()))
				.findFirst()
				.orElse(null);
		boolean alive = worker != null && worker.alive();
		String name = worker == null ? "" : worker.name();
		return new AgentSessionView(true, alive, session.getWorkerId(), name, "", "", "", "");
	}

	public String conversations(long projectId, int number, Long before) {
		return conversations(projectId, number, 0, before);
	}

	public String conversations(long projectId, int number, long agentId, Long before) {
		String path = "/sessions/" + number + "/conversations" + query(agentId, before);
		return read(projectId, number, agentId, path);
	}

	public String conversation(long projectId, int number, long seq) {
		return conversation(projectId, number, 0, seq);
	}

	public String conversation(long projectId, int number, long agentId, long seq) {
		return read(projectId, number, agentId, "/sessions/" + number + "/conversations/" + seq + query(agentId, null));
	}

	public String say(long projectId, int number, String body) {
		return say(projectId, number, 0, body);
	}

	public String say(long projectId, int number, long agentId, String body) {
		CallableWorker worker = require(projectId, number, agentId);
		WorkerConversationClient.Exchange exchange = conversations.post(worker.address(),
				"/sessions/" + number + "/messages" + query(agentId, null), body == null ? "" : body);
		return accepted(exchange, "消息没有送出去。");
	}

	public String file(long projectId, int number, String path) {
		return file(projectId, number, 0, path);
	}

	public String file(long projectId, int number, long agentId, String path) {
		String encoded = java.net.URLEncoder.encode(path == null ? "" : path, java.nio.charset.StandardCharsets.UTF_8);
		String query = query(agentId, null);
		String joiner = query.isEmpty() ? "?" : "&";
		return read(projectId, number, agentId, "/sessions/" + number + "/files" + query + joiner + "path=" + encoded);
	}

	public void stream(long projectId, int number, long seq, java.io.OutputStream out) {
		stream(projectId, number, 0, seq, out);
	}

	public void stream(long projectId, int number, long agentId, long seq, java.io.OutputStream out) {
		CallableWorker worker = require(projectId, number, agentId);
		try {
			conversations.pipe(worker.address(),
					"/sessions/" + number + "/conversations/" + seq + "/stream" + query(agentId, null), out);
		}
		catch (java.io.IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, detail(exception.getMessage(), "对话没有读到。"));
		}
	}

	private String read(long projectId, int number, String path) {
		return read(projectId, number, 0, path);
	}

	private String read(long projectId, int number, long agentId, String path) {
		CallableWorker worker = require(projectId, number, agentId);
		return accepted(conversations.get(worker.address(), path), "对话没有读到。");
	}

	private CallableWorker require(long projectId, int number, long agentId) {
		AgentSessionEntity session = sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, agentId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这张 issue 还没有 Agent 会话。"));
		return registry.live(session.getWorkerId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "绑定的 Worker 不存活，会话已中断。"));
	}

	private static String accepted(WorkerConversationClient.Exchange exchange, String fallback) {
		if (exchange.status() >= 200 && exchange.status() < 300) {
			return exchange.body();
		}
		HttpStatus status = HttpStatus.resolve(exchange.status());
		if (status == null || exchange.status() == 502) {
			status = HttpStatus.BAD_GATEWAY;
		}
		throw new ResponseStatusException(status, detail(exchange.body(), fallback));
	}

	private static String detail(String body, String fallback) {
		if (body == null || body.isBlank()) {
			return fallback;
		}
		int mark = body.indexOf("\"detail\"");
		if (mark >= 0) {
			int colon = body.indexOf(':', mark);
			int quote = body.indexOf('"', colon + 1);
			int end = quote < 0 ? -1 : body.indexOf('"', quote + 1);
			if (quote >= 0 && end > quote) {
				String text = body.substring(quote + 1, end).strip();
				if (!text.isEmpty()) {
					return text;
				}
			}
		}
		String text = body.strip();
		return text.length() > 180 ? text.substring(0, 180) : text;
	}

	private IssueAgentView seat(long projectId, int number, AgentEntity agent) {
		AgentSessionEntity session = sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, agent.getId())
				.orElse(null);
		if (session == null) {
			return new IssueAgentView(agent.getId(), agent.getName(), agent.getTag() == null ? "" : agent.getTag(), false, false, "", "");
		}
		return seat(agent, session);
	}

	private IssueAgentView seat(AgentEntity agent, AgentSessionEntity session) {
		WorkerRegistry.WorkerView worker = registry.list().stream()
				.filter(item -> item.id().equals(session.getWorkerId()))
				.findFirst()
				.orElse(null);
		boolean alive = worker != null && worker.alive();
		String name = worker == null ? "" : worker.name();
		return new IssueAgentView(agent.getId(), agent.getName(), agent.getTag() == null ? "" : agent.getTag(), true, alive,
				session.getWorkerId(), name);
	}

	private static String query(long agentId, Long before) {
		String text = "";
		if (agentId > 0) {
			text = "?agent=" + agentId;
		}
		if (before != null) {
			text = text + (text.isEmpty() ? "?" : "&") + "before=" + before;
		}
		return text;
	}

	public record IssueAgentView(long agentId, String name, String tag, boolean assigned, boolean alive, String workerId,
			String workerName) {
	}

}
