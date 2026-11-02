package dev.helix.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LdapAccountService {

	public static final String UID = "LDAP_UID";

	public static final String PASSWORD = "LDAP_PASSWORD";

	private final GitlabLoginRepository logins;

	private final GitlabSessionCredential credential;

	public LdapAccountService(GitlabLoginRepository logins, GitlabSessionCredential credential) {
		this.logins = logins;
		this.credential = credential;
	}

	@Transactional(readOnly = true)
	public View view(long gitlabUserId) {
		GitlabLoginEntity row = logins.findByGitlabUserId(gitlabUserId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "登录记录没有找到。"));
		return new View(row.getLdapUid());
	}

	@Transactional
	public View save(long gitlabUserId, String uid, String password) {
		String name = uid == null ? "" : uid.strip();
		String secret = password == null ? "" : password;
		if (name.isEmpty() || secret.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LDAP 用户名和密码都要写。");
		}
		GitlabLoginEntity row = logins.findByGitlabUserId(gitlabUserId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "登录记录没有找到。"));
		row.setLdapUid(name);
		row.setLdapPassword(secret);
		return new View(row.getLdapUid());
	}

	@Transactional
	public View clear(long gitlabUserId) {
		GitlabLoginEntity row = logins.findByGitlabUserId(gitlabUserId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "登录记录没有找到。"));
		row.setLdapUid("");
		row.setLdapPassword("");
		return new View("");
	}

	@Transactional(readOnly = true)
	public Map<String, String> env(long gitlabUserId) {
		return envOf(logins.findByGitlabUserId(gitlabUserId).orElse(null));
	}

	@Transactional(readOnly = true)
	public Map<String, String> env() {
		String token = credential.current();
		if (token == null || token.isBlank()) {
			return Map.of();
		}
		return envOf(logins.findByAccessToken(token).orElse(null));
	}

	private static Map<String, String> envOf(GitlabLoginEntity row) {
		if (row == null || row.getLdapUid().isBlank() || row.getLdapPassword().isBlank()) {
			return Map.of();
		}
		return Map.of(UID, row.getLdapUid(), PASSWORD, row.getLdapPassword());
	}

	public record View(String uid) {
	}

}
