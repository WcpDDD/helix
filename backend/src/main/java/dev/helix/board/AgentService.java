package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentService {

	private final AgentRepository agents;

	public AgentService(AgentRepository agents) {
		this.agents = agents;
	}

	@Transactional(readOnly = true)
	public List<AgentView> list(ProjectEntity project) {
		return agents.findByProject_IdOrderByNameAsc(project.getId()).stream().map(AgentService::view).toList();
	}

	@Transactional
	public AgentView create(ProjectEntity project, AgentDraft draft) {
		String name = name(draft == null ? null : draft.name());
		if (agents.existsByProject_IdAndName(project.getId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		AgentEntity agent = new AgentEntity(project, name, body(draft.body()));
		agent.replaceSkills(skills(draft.skills()));
		return view(agents.save(agent));
	}

	@Transactional
	public AgentView update(ProjectEntity project, long id, AgentDraft draft) {
		AgentEntity agent = agents.findByIdAndProject_Id(id, project.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String name = name(draft == null ? null : draft.name());
		if (!agent.getName().equals(name) && agents.existsByProject_IdAndName(project.getId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		agent.rename(name);
		agent.rewrite(body(draft == null ? null : draft.body()));
		agent.replaceSkills(skills(draft == null ? null : draft.skills()));
		return view(agent);
	}

	@Transactional
	public void delete(ProjectEntity project, long id) {
		AgentEntity agent = agents.findByIdAndProject_Id(id, project.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		agents.delete(agent);
	}

	private static AgentView view(AgentEntity agent) {
		List<String> skills = agent.getSkills().stream().map(AgentSkillEntity::getSkillPath).toList();
		return new AgentView(agent.getId(), agent.getName(), agent.getBody(), skills);
	}

	private static String name(String raw) {
		if (raw == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		String name = raw.strip();
		if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
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

	public record AgentView(long id, String name, String body, List<String> skills) {
	}

	public record AgentDraft(String name, String body, List<String> skills) {
	}

}
