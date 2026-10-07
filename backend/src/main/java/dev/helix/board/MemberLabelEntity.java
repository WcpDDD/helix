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
@Table(name = "member_label", uniqueConstraints = @UniqueConstraint(columnNames = { "project_id", "name" }))
public class MemberLabelEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private ProjectEntity project;

	@Column(nullable = false)
	private String name;

	protected MemberLabelEntity() {
	}

	public MemberLabelEntity(ProjectEntity project, String name) {
		this.project = project;
		this.name = name;
	}

	public String getName() {
		return name;
	}

}
