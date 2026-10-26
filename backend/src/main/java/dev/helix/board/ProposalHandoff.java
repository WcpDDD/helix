package dev.helix.board;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.worker.AgentSessionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 创建任务时的委托直接交给 proposal，由绑定的 worker 实际执行。manager 只服务目标模式。
 */
@Component
public class ProposalHandoff {

	private final AgentRepository agents;

	private final AgentSessionService sessions;

	private final ScheduleLog journal;

	private final JsonMapper json = new JsonMapper();

	public ProposalHandoff(AgentRepository agents, AgentSessionService sessions, ScheduleLog journal) {
		this.agents = agents;
		this.sessions = sessions;
		this.journal = journal;
	}

	public void ensure(ProjectEntity project) {
		proposal(project);
	}

	public void hand(ProjectEntity project, int iid) {
		AgentEntity proposal = proposal(project);
		sessions.create(project.getId(), iid, proposal.getId());
		ObjectNode body = json.createObjectNode();
		body.put("text", "读取这张 issue，按提案规范把提案写回来。");
		sessions.say(project.getId(), iid, proposal.getId(), json.writeValueAsString(body));
		journal.write("handoff", Map.of("project", project.getName(), "issue", iid, "tag", "proposal", "note", "交给 proposal 的会话。"));
	}

	private AgentEntity proposal(ProjectEntity project) {
		if (project == null || project.getId() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "没有配置 proposal。");
		}
		return agents.findWithSkillsByTag(project.getId(), "proposal")
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "没有配置 proposal。"));
	}

}
