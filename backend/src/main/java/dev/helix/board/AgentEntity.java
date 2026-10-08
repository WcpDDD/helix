package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "agent", uniqueConstraints = @UniqueConstraint(columnNames = { "project_id", "name" }))
public class AgentEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private ProjectEntity project;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, columnDefinition = "text")
	private String body;

	@OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position")
	private final List<AgentSkillEntity> skills = new ArrayList<>();

	protected AgentEntity() {
	}

	public AgentEntity(ProjectEntity project, String name, String body) {
		this.project = project;
		this.name = name;
		this.body = body;
	}

	public Long getId() {
		return id;
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

}
