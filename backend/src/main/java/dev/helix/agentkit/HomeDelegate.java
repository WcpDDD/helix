package dev.helix.agentkit;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.helix.board.AgentEntity;
import dev.helix.board.AgentRepository;
import dev.helix.board.AgentSkillEntity;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.work.ProjectWorkspaces;

import io.agentkit.api.env.Workspace;
import io.agentkit.api.tools.Tool;
import io.agentkit.api.tools.ToolParam;
import io.agentkit.execution.tools.AnnotatedToolFacade;

public class HomeDelegate {

	private final AgentRepository agents;

	private final ProjectRepository projects;

	private final ProjectWorkspaces workspaces;

	private final AgentKitRuntime hands;

	public HomeDelegate(AgentRepository agents, ProjectRepository projects, ProjectWorkspaces workspaces, AgentKitRuntime hands) {
		this.agents = agents;
		this.projects = projects;
		this.workspaces = workspaces;
		this.hands = hands;
	}

	public AnnotatedToolFacade tools() {
		return AnnotatedToolFacade.from(this);
	}

	@Tool(name = "delegate", description = "把任务交给当前项目里的一个 agent，并返回它的最终结果。uri 填用户附件地址，这个 agent 能看图。")
	public String delegate(Workspace workspace, @ToolParam(description = "agent 的名字，必须来自可用名单") String agent,
			@ToolParam(description = "交给这个 agent 的任务") String task,
			@ToolParam(description = "用户附件的 URI，没有就留空", required = false) String uri) {
		String wanted = agent == null ? "" : agent.strip();
		String brief = task == null ? "" : task.strip();
		if (wanted.isEmpty()) {
			return "要指定 agent 的名字。";
		}
		if (brief.isEmpty()) {
			return "要写清交给它的任务。";
		}
		ProjectEntity project = project(workspace);
		if (project == null) {
			return "没有对上当前项目。";
		}
		AgentEntity match = null;
		List<String> names = new ArrayList<>();
		for (AgentEntity candidate : agents.findWithSkills(project.getId())) {
			names.add(candidate.getName());
			if (candidate.getName().equals(wanted)) {
				match = candidate;
			}
		}
		if (match == null) {
			return names.isEmpty() ? "当前项目还没有 agent。" : "没有这个 agent。可用的是：" + String.join("、", names);
		}
		String prompt = match.getBody() == null || match.getBody().isBlank() ? "你是" + match.getName() + "。" : match.getBody();
		List<String> skills = new ArrayList<>();
		for (AgentSkillEntity skill : match.getSkills()) {
			if (skill.getSkillPath() != null && !skill.getSkillPath().isBlank()) {
				skills.add(skill.getSkillPath());
			}
		}
		List<URI> files = List.of();
		if (uri != null && !uri.isBlank()) {
			try {
				files = List.of(URI.create(uri.strip()));
			}
			catch (IllegalArgumentException exception) {
				return "附件地址不对。";
			}
		}
		try {
			AgentKitRuntime.RunResult result = hands.execute(prompt, "home", brief, Path.of(workspace.root()), Map.of(), skills, files);
			if (result.text() != null && !result.text().isBlank()) {
				return result.text();
			}
			if (result.failure() != null && !result.failure().isBlank()) {
				return "没有完成。";
			}
			return "没有得出结果。";
		}
		catch (RuntimeException exception) {
			return "没有完成。";
		}
	}

	private ProjectEntity project(Workspace workspace) {
		if (workspace == null || workspace.root() == null || workspace.root().isBlank()) {
			return null;
		}
		Path root = Path.of(workspace.root()).toAbsolutePath().normalize();
		for (ProjectEntity project : projects.findAll()) {
			Path opened = workspaces.opened(project);
			if (opened != null && opened.toAbsolutePath().normalize().equals(root)) {
				return project;
			}
		}
		return null;
	}

}
