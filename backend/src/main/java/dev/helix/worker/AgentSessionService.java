package dev.helix.worker;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.board.AgentEntity;
import dev.helix.board.AgentRepository;
import dev.helix.board.AgentSkillEntity;
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

	private final dev.helix.board.AgentAudience audience;

	private final dev.helix.auth.LdapAccountService ldap;

	private final dev.helix.auth.GitlabGitEnv git;

	private final JsonMapper json = new JsonMapper();

	public AgentSessionService(AgentSessionRepository sessions, WorkerRegistry registry, WorkerSessionClient workers,
			WorkerConversationClient conversations, SubtaskScheduler scheduler, AgentRepository definitions,
			dev.helix.board.AgentAudience audience, dev.helix.auth.LdapAccountService ldap,
			dev.helix.auth.GitlabGitEnv git) {
		this.sessions = sessions;
		this.registry = registry;
		this.workers = workers;
		this.conversations = conversations;
		this.scheduler = scheduler;
		this.definitions = definitions;
		this.audience = audience;
		this.ldap = ldap;
		this.git = git;
	}

	public AgentSessionView open(long projectId, int number) {
		return stored(projectId, number, "").map(this::view).orElseGet(() -> scheduler.session(projectId, number));
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
			workers.create(worker.address(), profiled("{\"issueNumber\":" + number + "}", null));
		}
		catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "创建 Agent 会话失败。");
		}
		try {
			return view(sessions.save(new AgentSessionEntity(projectId, number, "", worker.id())));
		}
		catch (DataIntegrityViolationException exception) {
			return stored(projectId, number, "").map(this::view)
					.orElseThrow(() -> exception);
		}
	}

	public List<IssueAgentView> roster(long projectId, int number, String username) {
		return definitions.findByProject_IdOrderByNameAsc(projectId).stream()
				.filter(agent -> "task".equals(agent.getScope()) && audience.allows(projectId, username, agent.getAccess()))
				.map(agent -> seat(projectId, number, agent))
				.toList();
	}

	public IssueAgentView create(long projectId, int number, String name) {
		return create(projectId, number, name, null);
	}

	public IssueAgentView create(long projectId, int number, String name, String username) {
		if (number <= 0 || name == null || name.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issue 或 agent 不对。");
		}
		AgentEntity agent = definitions.findWithSkillsByName(projectId, name)
				.filter(found -> "task".equals(found.getScope()) && audience.allows(projectId, username, found.getAccess()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个 agent。"));
		AgentSessionEntity existing = stored(projectId, number, agent.getName()).orElse(null);
		if (existing != null) {
			return seat(agent, existing);
		}
		Map<String, Long> load = sessions.findAll().stream()
				.collect(Collectors.groupingBy(AgentSessionEntity::getWorkerId, Collectors.counting()));
		CallableWorker worker = registry.lightest(load)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有可用的 Worker。"));
		try {
			workers.create(worker.address(), profiled("{\"issueNumber\":" + number + ",\"agent\":\"" + escape(agent.getName()) + "\"}", agent));
		}
		catch (RestClientException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "创建 Agent 会话失败。");
		}
		try {
			return seat(agent, sessions.save(new AgentSessionEntity(projectId, number, agent.getName(), worker.id())));
		}
		catch (DataIntegrityViolationException exception) {
			return stored(projectId, number, agent.getName())
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

	public String dispatch(String workerId, int number, String agentKey, String body) {
		String text = taskText(body);
		long projectId = project(workerId, number);
		AgentEntity agent = agent(projectId, agentKey);
		String payload = profiled("{\"text\":\"" + escape(text) + "\"}", agent);
		AgentSessionEntity existing = stored(projectId, number, agent.getName()).orElse(null);
		if (existing != null) {
			CallableWorker worker = registry.live(existing.getWorkerId())
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "绑定的 Worker 不存活，会话已中断。"));
			return accepted(conversations.post(worker.address(), messagePath(number, agent.getName()), payload), "任务没有派出去。");
		}
		Map<String, Long> load = sessions.findAll().stream()
				.collect(Collectors.groupingBy(AgentSessionEntity::getWorkerId, Collectors.counting()));
		CallableWorker worker = registry.lightest(load)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有可用的 Worker。"));
		String response = accepted(conversations.post(worker.address(), messagePath(number, agent.getName()), payload), "任务没有派出去。");
		try {
			sessions.save(new AgentSessionEntity(projectId, number, agent.getName(), worker.id()));
		}
		catch (DataIntegrityViolationException exception) {
			AgentSessionEntity saved = stored(projectId, number, agent.getName())
					.orElseThrow(() -> exception);
			if (!saved.getWorkerId().equals(worker.id())) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "这个 agent 已经在别的 worker 上。");
			}
		}
		return response;
	}

	public String catalog(String workerId, int number, String agentKey, Long before) {
		long projectId = project(workerId, number);
		AgentEntity agent = agent(projectId, agentKey);
		if (stored(projectId, number, agent.getName()).isEmpty()) {
			return "{\"messages\":[],\"hasMore\":false}";
		}
		return conversations(projectId, number, agent.getName(), before);
	}

	public String transcript(String workerId, int number, String agentKey, long seq) {
		if (seq <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话位置不对。");
		}
		long projectId = project(workerId, number);
		AgentEntity agent = agent(projectId, agentKey);
		if (stored(projectId, number, agent.getName()).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这条对话没有。");
		}
		return conversation(projectId, number, agent.getName(), seq);
	}

	public String conversations(long projectId, int number, Long before) {
		return conversations(projectId, number, "", before);
	}

	public String conversations(long projectId, int number, String agentName, Long before) {
		String path = "/sessions/" + number + "/conversations" + query(agentName, before);
		return read(projectId, number, agentName, path);
	}

	public String conversation(long projectId, int number, long seq) {
		return conversation(projectId, number, "", seq);
	}

	public String conversation(long projectId, int number, String agentName, long seq) {
		return read(projectId, number, agentName, "/sessions/" + number + "/conversations/" + seq + query(agentName, null));
	}

	public String say(long projectId, int number, String body) {
		return say(projectId, number, "", body);
	}

	public String say(long projectId, int number, String agentName, String body) {
		CallableWorker worker = require(projectId, number, agentName);
		AgentEntity agent = agentName == null || agentName.isBlank() ? null : definitions.findWithSkillsByName(projectId, agentName).orElse(null);
		WorkerConversationClient.Exchange exchange = conversations.post(worker.address(),
				"/sessions/" + number + "/messages" + query(agentName, null), profiled(body, agent));
		return accepted(exchange, "消息没有送出去。");
	}

	public String file(long projectId, int number, String path) {
		return file(projectId, number, "", path);
	}

	public String file(long projectId, int number, String agentName, String path) {
		String encoded = java.net.URLEncoder.encode(path == null ? "" : path, java.nio.charset.StandardCharsets.UTF_8);
		String query = query(agentName, null);
		String joiner = query.isEmpty() ? "?" : "&";
		return read(projectId, number, agentName, "/sessions/" + number + "/files" + query + joiner + "path=" + encoded);
	}

	public void stream(long projectId, int number, long seq, java.io.OutputStream out) {
		stream(projectId, number, "", seq, out);
	}

	public void stream(long projectId, int number, String agentName, long seq, java.io.OutputStream out) {
		CallableWorker worker = require(projectId, number, agentName);
		try {
			conversations.pipe(worker.address(),
					"/sessions/" + number + "/conversations/" + seq + "/stream" + query(agentName, null), out);
		}
		catch (java.io.IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, detail(exception.getMessage(), "对话没有读到。"));
		}
	}

	private String read(long projectId, int number, String path) {
		return read(projectId, number, "", path);
	}

	private String read(long projectId, int number, String agentName, String path) {
		CallableWorker worker = require(projectId, number, agentName);
		return accepted(conversations.get(worker.address(), path), "对话没有读到。");
	}

	private long project(String workerId, int number) {
		if (number <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issue 编号不对。");
		}
		registry.live(workerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "这个 worker 不存活。"));
		return sessions.findByWorkerIdAndIssueNumber(workerId, number).stream()
				.findFirst()
				.map(AgentSessionEntity::getProjectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这张 issue 不在这个 worker 上。"));
	}

	private AgentEntity agent(long projectId, String agentKey) {
		if (agentKey == null || agentKey.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有这个 agent。");
		}
		String key = agentKey.strip();
		return definitions.findWithSkillsByName(projectId, key)
				.or(() -> definitions.findWithSkillsByTag(projectId, key))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个 agent。"));
	}

	private static String taskText(String body) {
		if (body == null || body.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
		}
		int mark = body.indexOf("\"text\"");
		if (mark < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
		}
		int colon = body.indexOf(':', mark);
		int quote = body.indexOf('"', colon + 1);
		if (colon < 0 || quote < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
		}
		StringBuilder text = new StringBuilder();
		boolean escaped = false;
		for (int i = quote + 1; i < body.length(); i++) {
			char current = body.charAt(i);
			if (escaped) {
				text.append(switch (current) {
					case 'n' -> '\n';
					case 'r' -> '\r';
					case 't' -> '\t';
					default -> current;
				});
				escaped = false;
				continue;
			}
			if (current == '\\') {
				escaped = true;
				continue;
			}
			if (current == '"') {
				String value = text.toString().strip();
				if (value.isEmpty()) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
				}
				return value;
			}
			text.append(current);
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
	}

	private String profiled(String body, AgentEntity agent) {
		JsonNode parsed;
		try {
			parsed = body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
		}
		catch (RuntimeException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息格式不对。");
		}
		if (!(parsed instanceof ObjectNode node)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息格式不对。");
		}
		node.put("agentBody", agent == null || agent.getBody() == null ? "" : agent.getBody());
		Map<String, String> userEnv = new java.util.LinkedHashMap<>(ldap.env());
		userEnv.putAll(git.current());
		if (!userEnv.isEmpty()) {
			ObjectNode vars = node.putObject("userEnv");
			userEnv.forEach(vars::put);
		}
		ArrayNode skills = node.putArray("skills");
		if (agent != null) {
			for (AgentSkillEntity skill : agent.getSkills()) {
				String path = skill.getSkillPath();
				if (path != null && !path.isBlank()) {
					skills.add(path.strip());
				}
			}
		}
		return json.writeValueAsString(node);
	}

	private static String messagePath(int number, String agentName) {
		return "/sessions/" + number + "/messages?agent=" + encode(agentName);
	}

	private static String encode(String value) {
		return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private java.util.Optional<AgentSessionEntity> stored(long projectId, int number, String agentName) {
		String key = agentName == null ? "" : agentName;
		java.util.Optional<AgentSessionEntity> named = sessions.findByProjectIdAndIssueNumberAndAgentName(projectId, number, key);
		if (named.isPresent() || !key.isEmpty()) {
			return named;
		}
		return sessions.findByProjectIdAndIssueNumberAndAgentId(projectId, number, 0);
	}

	private CallableWorker require(long projectId, int number, String agentName) {
		AgentSessionEntity session = stored(projectId, number, agentName)
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
		AgentSessionEntity session = stored(projectId, number, agent.getName()).orElse(null);
		if (session == null) {
			return new IssueAgentView(agent.getName(), agent.getTag() == null ? "" : agent.getTag(), false, false, "", "");
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
		return new IssueAgentView(agent.getName(), agent.getTag() == null ? "" : agent.getTag(), true, alive,
				session.getWorkerId(), name);
	}

	private static String query(String agentName, Long before) {
		String text = "";
		if (agentName != null && !agentName.isBlank()) {
			text = "?agent=" + encode(agentName);
		}
		if (before != null) {
			text = text + (text.isEmpty() ? "?" : "&") + "before=" + before;
		}
		return text;
	}

	public record IssueAgentView(String name, String tag, boolean assigned, boolean alive, String workerId,
			String workerName) {
	}

}
