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
@Table(name = "issue_edge", uniqueConstraints = @UniqueConstraint(columnNames = { "from_issue_id", "to_issue_id" }))
public class IssueEdgeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private TaskEntity task;

	@ManyToOne(optional = false)
	@JoinColumn(name = "from_issue_id", nullable = false)
	private IssueEntity fromIssue;

	@ManyToOne(optional = false)
	@JoinColumn(name = "to_issue_id", nullable = false)
	private IssueEntity toIssue;

	@Column(name = "needs_contract", nullable = false)
	private boolean needsContract;

	@Column(name = "contract_ref")
	private String contractRef;

	protected IssueEdgeEntity() {
	}

	public IssueEdgeEntity(TaskEntity task, IssueEntity fromIssue, IssueEntity toIssue, boolean needsContract,
			String contractRef) {
		this.task = task;
		this.fromIssue = fromIssue;
		this.toIssue = toIssue;
		this.needsContract = needsContract;
		this.contractRef = contractRef;
	}

	public Long getTaskId() {
		return task.getId();
	}

	public int getFromNumber() {
		return fromIssue.getGitIssueNumber();
	}

	public int getToNumber() {
		return toIssue.getGitIssueNumber();
	}

	public boolean isNeedsContract() {
		return needsContract;
	}

	public String getContractRef() {
		return contractRef;
	}

}
