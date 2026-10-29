package dev.helix.agentkit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "home_session")
public class HomeSessionEntity {

	@Id
	@Column(length = 36)
	private String id;

	@Column(name = "project_id", nullable = false)
	private long projectId;

	@Column(name = "user_id", nullable = false)
	private long userId;

	@Column(nullable = false)
	private String username;

	@Column(name = "thread_id")
	private String threadId;

	@Column(name = "messages_json", nullable = false, columnDefinition = "TEXT")
	private String messagesJson;

	@Column(name = "updated_at", nullable = false)
	private String updatedAt;

	@Column(name = "current_session", nullable = false)
	private boolean currentSession;

	protected HomeSessionEntity() {
	}

	public HomeSessionEntity(String id, long projectId, long userId, String username) {
		this.id = id;
		this.projectId = projectId;
		this.userId = userId;
		this.username = username;
	}

	public void remember(String threadId, String messagesJson, String updatedAt, boolean current) {
		this.threadId = threadId;
		this.messagesJson = messagesJson == null ? "[]" : messagesJson;
		this.updatedAt = updatedAt;
		this.currentSession = current;
	}

	public void current(boolean current) {
		this.currentSession = current;
	}

	public String getId() {
		return id;
	}

	public long getProjectId() {
		return projectId;
	}

	public long getUserId() {
		return userId;
	}

	public String getUsername() {
		return username;
	}

	public String getThreadId() {
		return threadId;
	}

	public String getMessagesJson() {
		return messagesJson;
	}

	public String getUpdatedAt() {
		return updatedAt;
	}

	public boolean isCurrentSession() {
		return currentSession;
	}

}
