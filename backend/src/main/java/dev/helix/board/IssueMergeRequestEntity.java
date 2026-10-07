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
@Table(name = "issue_merge_request", uniqueConstraints = @UniqueConstraint(columnNames = { "issue_id", "web_url" }))
public class IssueMergeRequestEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "issue_id", nullable = false)
	private IssueEntity issue;

	@Column(nullable = false)
	private String iid;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String state;

	@Column(name = "web_url", nullable = false)
	private String webUrl;

	protected IssueMergeRequestEntity() {
	}

	public IssueMergeRequestEntity(IssueEntity issue, String iid, String title, String state, String webUrl) {
		this.issue = issue;
		this.iid = iid;
		this.title = title;
		this.state = state;
		this.webUrl = webUrl;
	}

	public Long getIssueId() {
		return issue.getId();
	}

	public String getIid() {
		return iid;
	}

	public String getTitle() {
		return title;
	}

	public String getState() {
		return state;
	}

	public String getWebUrl() {
		return webUrl;
	}

}
