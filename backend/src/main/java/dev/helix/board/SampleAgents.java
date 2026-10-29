package dev.helix.board;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SampleAgents implements ApplicationRunner {

	static final String BUG = "bug分析";

	static final String ANSWER = "兜底答疑";

	private static final String BUG_BODY = """
			你负责分析 bug。根据现象在当前仓库里定位相关代码，说明原因、影响和最小改法。
			不要扩大范围去做无关修改。最后用中文给出结论。""";

	private static final String ANSWER_BODY = """
			你负责回答当前项目里其他 agent 不接的问题。先看仓库里已有的说明和代码，再回答。
			不知道就直说不知道。用中文回答。""";

	private final ProjectRepository projects;

	private final AgentRepository agents;

	public SampleAgents(ProjectRepository projects, AgentRepository agents) {
		this.projects = projects;
		this.agents = agents;
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

	@Transactional
	public void ensure(ProjectEntity project) {
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
