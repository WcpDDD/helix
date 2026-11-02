package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

import dev.helix.work.ProjectWorkspaces;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentService {

	private final AgentRepository agents;

	private final SampleAgents samples;

	private final ProjectWorkspaces workspaces;

	private final AgentAudience audience;

	public AgentService(AgentRepository agents) {
		this(agents, null, null, null);
	}

	@Autowired
	public AgentService(AgentRepository agents, SampleAgents samples, ProjectWorkspaces workspaces, AgentAudience audience) {
		this.agents = agents;
		this.samples = samples;
		this.workspaces = workspaces;
		this.audience = audience;
	}

	@Transactional(readOnly = true)
	public AgentView require(ProjectEntity project, String name) {
		ready(project);
		AgentEntity agent = agents.findByProject_IdAndName(project.getId(), name)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return view(agent);
	}

	public List<AgentView> list(ProjectEntity project) {
		return list(project, null);
	}

	public List<AgentView> list(ProjectEntity project, String username) {
		if (workspaces != null && workspaces.opened(project) == null) {
			return List.of();
		}
		if (samples != null) {
			samples.ensure(project);
		}
		boolean catalog = audience == null || audience.seesCatalog(project.getId(), username);
		return agents.findByProject_IdOrderByNameAsc(project.getId()).stream()
				.filter(agent -> catalog || AgentAccess.allows(audience.accessOf(project.getId(), username), agent.getAccess()))
				.map(AgentService::view)
				.toList();
	}

	@Transactional
	public AgentView create(ProjectEntity project, AgentDraft draft) {
		ready(project);
		String name = name(draft == null ? null : draft.name());
		if (agents.existsByProject_IdAndName(project.getId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		String scope = AgentAccess.scope(draft.scope(), draft.tag());
		String tag = "global".equals(scope) ? null : tag(draft.tag());
		claim(project, null, tag);
		AgentEntity agent = new AgentEntity(project, name, body(draft.body()));
		agent.replaceSkills(skills(draft.skills()));
		agent.retag(tag);
		agent.rescope(scope);
		agent.replaceAccess(AgentAccess.access(draft.access()));
		return view(agents.save(agent));
	}

	@Transactional
	public AgentView update(ProjectEntity project, String current, AgentDraft draft) {
		ready(project);
		AgentEntity agent = agents.findByProject_IdAndName(project.getId(), current)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String name = name(draft == null ? null : draft.name());
		if (!agent.getName().equals(name) && agents.existsByProject_IdAndName(project.getId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		String scope = AgentAccess.scope(draft == null ? null : draft.scope(), draft == null ? null : draft.tag());
		String tag = "global".equals(scope) ? null : tag(draft == null ? null : draft.tag());
		claim(project, agent.getName(), tag);
		String previous = agent.getName();
		agent.rename(name);
		agent.rewrite(body(draft == null ? null : draft.body()));
		agent.replaceSkills(skills(draft == null ? null : draft.skills()));
		agent.retag(tag);
		agent.rescope(scope);
		agent.replaceAccess(AgentAccess.access(draft == null ? null : draft.access()));
		agents.save(agent, previous);
		return view(agent);
	}

	@Transactional
	public void delete(ProjectEntity project, String name) {
		ready(project);
		AgentEntity agent = agents.findByProject_IdAndName(project.getId(), name)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		agents.delete(agent);
	}

	private void ready(ProjectEntity project) {
		if (workspaces != null && workspaces.opened(project) == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "主分支还在准备。");
		}
	}

	private static AgentView view(AgentEntity agent) {
		List<String> skills = agent.getSkills().stream().map(AgentSkillEntity::getSkillPath).toList();
		return new AgentView(agent.getName(), agent.getBody(), skills, agent.getTag(), agent.getScope(), agent.getAccess());
	}

	private static String name(String raw) {
		if (raw == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		String name = raw.strip();
		if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		if (name.contains("/") || name.contains("\\") || ".".equals(name) || "..".equals(name)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名字不能包含路径。");
		}
		return name;
	}

	private static String body(String raw) {
		String body = raw == null ? "" : raw;
		if (body.length() > 200_000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		return body;
	}

	private static List<String> skills(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<String> paths = new ArrayList<>();
		for (String item : raw) {
			String path;
			try {
				path = SkillIndex.require(item);
			}
			catch (IllegalArgumentException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			}
			if (!path.endsWith("/SKILL.md")) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			}
			if (!paths.contains(path)) {
				paths.add(path);
			}
		}
		return List.copyOf(paths);
	}

	private void claim(ProjectEntity project, String currentName, String tag) {
		if (tag == null) {
			return;
		}
		boolean taken = currentName == null
				? agents.existsByProject_IdAndTag(project.getId(), tag)
				: agents.existsByProject_IdAndTagAndNameNot(project.getId(), tag, currentName);
		if (taken) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, tag + " 在这个项目里只能有一个。");
		}
	}

	private static String tag(String raw) {
		try {
			return AgentTag.normalize(raw);
		}
		catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
	}

	public record AgentView(String name, String body, List<String> skills, String tag, String scope, List<String> access) {
	}

	public record AgentDraft(String name, String body, List<String> skills, String tag, String scope, List<String> access) {

		public AgentDraft(String name, String body, List<String> skills, String tag) {
			this(name, body, skills, tag, null, null);
		}
	}

}
