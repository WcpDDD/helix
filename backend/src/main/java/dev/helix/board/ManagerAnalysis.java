package dev.helix.board;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.worker.AgentSessionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ManagerAnalysis {

	private static final Logger log = LoggerFactory.getLogger(ManagerAnalysis.class);

	private final AgentRepository agents;

	private final AgentSessionService sessions;

	private final MockRuntime runtime;

	private final ScheduleLog journal;

	private final JsonMapper json = new JsonMapper();

	public ManagerAnalysis(AgentRepository agents, AgentSessionService sessions, MockRuntime runtime, ScheduleLog journal) {
		this.agents = agents;
		this.sessions = sessions;
		this.runtime = runtime;
		this.journal = journal;
	}

	public void analyze(String accessToken, ProjectEntity project, int iid, List<String> labels) {
		if (project.getId() == null) {
			return;
		}
		if (labels != null && labels.contains(ProposalDraft.HITL)) {
			journal.write("coordinate", Map.of("issue", iid, "action", "stop", "note", "已经派给人。"));
			return;
		}
		Optional<MockRuntime.Lease> busy = runtime.running(project.getId(), iid);
		if (busy.isPresent()) {
			journal.write("coordinate", Map.of("issue", iid, "action", "stop", "tag", busy.get().tag(), "note",
					busy.get().tag() + " 还在运行，到 " + MockRuntime.format(busy.get().until()) + "。"));
			return;
		}
		AgentEntity manager = agents.findWithSkillsByTag(project.getId(), "manager").orElse(null);
		if (manager == null) {
			journal.write("coordinate", Map.of("issue", iid, "action", "stop", "note", "没有配置 manager。"));
			return;
		}
		try {
			sessions.create(project.getId(), iid, manager.getId());
			ObjectNode body = json.createObjectNode();
			body.put("text", "做这一轮。");
			sessions.say(project.getId(), iid, manager.getId(), json.writeValueAsString(body));
		}
		catch (ResponseStatusException exception) {
			String reason = exception.getReason() == null ? "manager 会话没有送出去。" : exception.getReason();
			log.warn("manager 会话没有送出去 #{}：{}", iid, reason);
			journal.write("coordinate", Map.of("issue", iid, "action", "stop", "tag", "manager", "note", reason));
			return;
		}
		journal.write("coordinate", Map.of("issue", iid, "action", "ask", "tag", "manager", "note", "交给 manager 的会话。"));
	}

}
