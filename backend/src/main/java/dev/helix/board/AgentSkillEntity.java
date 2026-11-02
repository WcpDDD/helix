package dev.helix.board;

public class AgentSkillEntity {

	private final AgentEntity agent;

	private final String skillPath;

	private final int position;

	public AgentSkillEntity(AgentEntity agent, String skillPath, int position) {
		this.agent = agent;
		this.skillPath = skillPath;
		this.position = position;
	}

	public String getSkillPath() {
		return skillPath;
	}

}
