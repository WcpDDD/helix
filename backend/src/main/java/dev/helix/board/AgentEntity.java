package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

public class AgentEntity {

	private ProjectEntity project;

	private String name;

	private String body;

	private final List<AgentSkillEntity> skills = new ArrayList<>();

	private String tag;

	private String scope = "task";

	private final List<String> access = new ArrayList<>();

	protected AgentEntity() {
	}

	public AgentEntity(ProjectEntity project, String name, String body) {
		this.project = project;
		this.name = name;
		this.body = body;
	}

	public ProjectEntity getProject() {
		return project;
	}

	public String getName() {
		return name;
	}

	public String getBody() {
		return body;
	}

	public List<AgentSkillEntity> getSkills() {
		return skills;
	}

	public String getTag() {
		return tag;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void rewrite(String body) {
		this.body = body;
	}

	public void replaceSkills(List<String> paths) {
		skills.clear();
		for (int index = 0; index < paths.size(); index++) {
			skills.add(new AgentSkillEntity(this, paths.get(index), index));
		}
	}

	public void retag(String tag) {
		this.tag = tag;
	}

	public String getScope() {
		return scope == null || scope.isBlank() ? "task" : scope;
	}

	public void rescope(String scope) {
		this.scope = scope;
	}

	public List<String> getAccess() {
		return List.copyOf(access);
	}

	public void replaceAccess(List<String> roles) {
		access.clear();
		if (roles != null) {
			access.addAll(roles);
		}
	}

}
