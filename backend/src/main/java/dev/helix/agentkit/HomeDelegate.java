package dev.helix.agentkit;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

	private final ConcurrentHashMap<String, Set<String>> permitted = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, Map<String, String>> variables = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, String> callers = new ConcurrentHashMap<>();

	public HomeDelegate(AgentRepository agents, ProjectRepository projects, ProjectWorkspaces workspaces, AgentKitRuntime hands) {
		this.agents = agents;
		this.projects = projects;
		this.workspaces = workspaces;
		this.hands = hands;
	}

	public AnnotatedToolFacade tools() {
		return AnnotatedToolFacade.from(this);
	}

	public void permit(Path workspace, List<String> names) {
		if (workspace == null) {
			return;
		}
		permitted.put(workspace.toAbsolutePath().normalize().toString(), Set.copyOf(names));
	}

	public void variables(Path workspace, Map<String, String> env) {
		if (workspace == null) {
			return;
		}
		variables.put(workspace.toAbsolutePath().normalize().toString(), env == null ? Map.of() : Map.copyOf(env));
	}

	public void caller(Path workspace, String userId) {
		if (workspace == null) {
			return;
		}
		String id = userId == null ? "" : userId.strip();
		if (!id.isEmpty()) {
			callers.put(workspace.toAbsolutePath().normalize().toString(), id);
		}
	}

	@Tool(name = "delegate", description = "把任务交给当前项目里的一个 agent，并返回它的最终结果。这个 agent 看不到当前对话，context 要带上它需要的前情。uri 填用户附件地址，这个 agent 能看图。")
	public String delegate(Workspace workspace, @ToolParam(description = "agent 的名字，必须来自可用名单") String agent,
			@ToolParam(description = "交给这个 agent 的任务") String task,
			@ToolParam(description = "这个 agent 没有之前的对话。写上完成任务需要的前情：用户原话、已经做过的事、失败原因、关键结果。") String context,
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
		Set<String> allowed = permitted.get(Path.of(workspace.root()).toAbsolutePath().normalize().toString());
		AgentEntity match = null;
		List<String> names = new ArrayList<>();
		for (AgentEntity candidate : agents.findWithSkills(project.getId())) {
			if (!"global".equals(candidate.getScope())) {
				continue;
			}
			if (allowed != null && !allowed.contains(candidate.getName())) {
				continue;
			}
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
			String key = Path.of(workspace.root()).toAbsolutePath().normalize().toString();
			Map<String, String> env = variables.getOrDefault(key, Map.of());
			String caller = callers.getOrDefault(key, "home");
			AgentKitRuntime.RunResult result = hands.execute(prompt, caller, handed(brief, context), Path.of(workspace.root()), env, skills, files);
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

	private static String handed(String task, String context) {
		String prior = context == null ? "" : context.strip();
		if (prior.isEmpty()) {
			return task;
		}
		return "上下文：\n" + prior + "\n\n任务：\n" + task;
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
