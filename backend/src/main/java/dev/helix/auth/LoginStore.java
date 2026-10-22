package dev.helix.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginStore {

	private final GitlabLoginRepository logins;

	private final SecureRandom random = new SecureRandom();

	public LoginStore(GitlabLoginRepository logins) {
		this.logins = logins;
	}

	@Transactional
	public String save(GitlabLogin login) {
		GitlabUser user = login.user();
		GitlabLoginEntity row = logins.findByGitlabUserId(user.id()).orElseGet(GitlabLoginEntity::new);
		if (row.getId() == null) {
			row.setId(newId());
		}
		row.setGitlabUserId(user.id());
		row.setUsername(user.username());
		row.setName(user.name());
		row.setAvatarUrl(user.avatarUrl());
		row.setAccessToken(login.accessToken());
		row.setRefreshToken(login.refreshToken());
		row.setExpiresAt(login.expiresAt());
		row.setUpdatedAt(System.currentTimeMillis());
		return logins.save(row).getId();
	}

	@Transactional
	public void updateTokens(String id, String accessToken, String refreshToken, long expiresAt) {
		GitlabLoginEntity row = logins.findById(id).orElse(null);
		if (row == null) {
			return;
		}
		row.setAccessToken(accessToken);
		row.setRefreshToken(refreshToken);
		row.setExpiresAt(expiresAt);
		row.setUpdatedAt(System.currentTimeMillis());
		logins.save(row);
	}

	public Optional<GitlabLoginEntity> find(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		return logins.findById(id);
	}

	public Optional<GitlabLoginEntity> latest() {
		return logins.findFirstByOrderByUpdatedAtDesc();
	}

	@Transactional
	public void delete(String id) {
		if (id != null && logins.existsById(id)) {
			logins.deleteById(id);
		}
	}

	private String newId() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
