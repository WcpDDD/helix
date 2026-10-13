package dev.helix.worker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "subtask_route")
public class SubtaskRouteEntity {

	@Id
	@Column(name = "issue_id")
	private Long issueId;

	@Column(name = "worker_id", nullable = false)
	private String workerId;

	@Column(name = "agent_id", nullable = false)
	private Long agentId;

	@Column(name = "system_prompt", nullable = false, columnDefinition = "text")
	private String systemPrompt;

	@Column(nullable = false, columnDefinition = "text")
	private String message;

	private String workspace;

	@Column(name = "route_state", nullable = false)
	private String routeState;

	private String phase;

	@Column(name = "result_text", columnDefinition = "text")
	private String resultText;

	@Column(columnDefinition = "text")
	private String failure;

	protected SubtaskRouteEntity() {
	}

	public SubtaskRouteEntity(Long issueId, String workerId, Long agentId, String systemPrompt, String message) {
		this.issueId = issueId;
		this.workerId = workerId;
		this.agentId = agentId;
		this.systemPrompt = systemPrompt;
		this.message = message;
		this.routeState = "pending";
	}

	public Long getIssueId() {
		return issueId;
	}

	public String getWorkerId() {
		return workerId;
	}

	public Long getAgentId() {
		return agentId;
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public String getMessage() {
		return message;
	}

	public String getWorkspace() {
		return workspace;
	}

	public String getRouteState() {
		return routeState;
	}

	public String getPhase() {
		return phase;
	}

	public String getResultText() {
		return resultText;
	}

	public String getFailure() {
		return failure;
	}

	public void bind(String workspace) {
		this.workspace = workspace;
		this.routeState = "bound";
	}

	public void interrupt() {
		this.routeState = "interrupted";
	}

	public void resume() {
		this.routeState = workspace == null || workspace.isBlank() ? "pending" : "bound";
	}

	public void finish(String phase, String resultText, String failure) {
		this.phase = phase;
		this.resultText = resultText;
		this.failure = failure;
	}

}
