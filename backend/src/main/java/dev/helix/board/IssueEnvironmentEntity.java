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
@Table(name = "issue_environment", uniqueConstraints = @UniqueConstraint(columnNames = { "issue_id", "url" }))
public class IssueEnvironmentEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "issue_id", nullable = false)
	private IssueEntity issue;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String url;

	protected IssueEnvironmentEntity() {
	}

	public IssueEnvironmentEntity(IssueEntity issue, String name, String url) {
		this.issue = issue;
		this.name = name;
		this.url = url;
	}

	public Long getIssueId() {
		return issue.getId();
	}

	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
	}

}
