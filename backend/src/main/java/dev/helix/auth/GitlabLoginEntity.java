package dev.helix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "gitlab_login")
public class GitlabLoginEntity {

	@Id
	private String id;

	@Column(name = "gitlab_user_id", nullable = false, unique = true)
	private long gitlabUserId;

	@Column(nullable = false)
	private String username;

	@Column(nullable = false)
	private String name;

	@Column(name = "avatar_url")
	private String avatarUrl;

	@Column(name = "access_token", nullable = false, length = 2048)
	private String accessToken;

	@Column(name = "refresh_token", length = 2048)
	private String refreshToken;

	@Column(name = "expires_at", nullable = false)
	private long expiresAt;

	@Column(name = "updated_at", nullable = false)
	private long updatedAt;

	protected GitlabLoginEntity() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public long getGitlabUserId() {
		return gitlabUserId;
	}

	public void setGitlabUserId(long gitlabUserId) {
		this.gitlabUserId = gitlabUserId;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public void setAvatarUrl(String avatarUrl) {
		this.avatarUrl = avatarUrl;
	}

	public String getAccessToken() {
		return accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	public String getRefreshToken() {
		return refreshToken;
	}

	public void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
	}

	public long getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(long expiresAt) {
		this.expiresAt = expiresAt;
	}

	public long getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(long updatedAt) {
		this.updatedAt = updatedAt;
	}

	public GitlabUser user() {
		return new GitlabUser(gitlabUserId, username, name, avatarUrl);
	}

}
