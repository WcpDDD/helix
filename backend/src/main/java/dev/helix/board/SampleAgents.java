package dev.helix.board;

import dev.helix.work.ProjectWorkspaces;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SampleAgents implements ApplicationRunner {

	static final String BUG = "bug分析";

	static final String ANSWER = "兜底答疑";

	private static final String BUG_BODY = """
			分析已经发生的 bug，定位根因后停，不改代码。

			方向在 `skills/bug-analysis/SKILL.md`。报错链路在 `skills/skywalking-errors/SKILL.md`。服务名以 SkyWalking 里探测到的为准，地图上的名字只作关键词。

			结论用中文。能指到一处因果就写根因。指不到就把材料交出去。不补原因，不写改法。""";

	private static final String ANSWER_BODY = """
			回答专职 agent 不接的问题。先看已有说明，不知道就直说。

			先读 workspace 根的 `AGENTS.md`，再用仓库里已经写明的文档和代码核对。回答停在问的那一件事上。

			不分析 bug，不查报错链路，不去环境复现，不改代码。用中文回答。""";

	private final ProjectRepository projects;

	private final AgentRepository agents;

	private final ProjectWorkspaces workspaces;

	public SampleAgents(ProjectRepository projects, AgentRepository agents, ProjectWorkspaces workspaces) {
		this.projects = projects;
		this.agents = agents;
		this.workspaces = workspaces;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAll();
	}

	@Transactional
	public void ensureAll() {
		for (ProjectEntity project : projects.findAll()) {
			ensure(project);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void ensure(ProjectEntity project) {
		if (workspaces.opened(project) == null) {
			return;
		}
		add(project, BUG, BUG_BODY);
		add(project, ANSWER, ANSWER_BODY);
	}

	private void add(ProjectEntity project, String name, String body) {
		if (agents.existsByProject_IdAndName(project.getId(), name)) {
			return;
		}
		agents.save(new AgentEntity(project, name, body));
	}

}
