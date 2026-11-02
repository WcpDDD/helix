package dev.helix.worker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "agent_session", uniqueConstraints = @UniqueConstraint(columnNames = { "project_id", "issue_number", "agent_id" }))
public class AgentSessionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "project_id", nullable = false)
	private long projectId;

	@Column(name = "issue_number", nullable = false)
	private int issueNumber;

	@Column(name = "agent_id")
	private Long agentId;

	@Column(name = "agent_name")
	private String agentName;

	@Column(name = "worker_id", nullable = false)
	private String workerId;

	protected AgentSessionEntity() {
	}

	public AgentSessionEntity(long projectId, int issueNumber, String agentName, String workerId) {
		this.projectId = projectId;
		this.issueNumber = issueNumber;
		this.agentName = agentName == null ? "" : agentName;
		this.agentId = this.agentName.isEmpty() ? 0L : null;
		this.workerId = workerId;
	}

	public long getProjectId() {
		return projectId;
	}

	public int getIssueNumber() {
		return issueNumber;
	}

	public long getAgentId() {
		return agentId == null ? 0 : agentId;
	}

	public String getAgentName() {
		return agentName == null ? "" : agentName;
	}

	public String getWorkerId() {
		return workerId;
	}

}
