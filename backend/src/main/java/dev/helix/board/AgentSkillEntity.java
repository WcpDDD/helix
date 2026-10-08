package dev.helix.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "agent_skill", uniqueConstraints = @UniqueConstraint(columnNames = { "agent_id", "skill_path" }))
public class AgentSkillEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "agent_id", nullable = false)
	private AgentEntity agent;

	@Column(name = "skill_path", nullable = false)
	private String skillPath;

	@Column(nullable = false)
	private int position;

	protected AgentSkillEntity() {
	}

	public AgentSkillEntity(AgentEntity agent, String skillPath, int position) {
		this.agent = agent;
		this.skillPath = skillPath;
		this.position = position;
	}

	public String getSkillPath() {
		return skillPath;
	}

}
