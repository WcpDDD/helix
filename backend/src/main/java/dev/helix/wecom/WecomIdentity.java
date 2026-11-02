package dev.helix.wecom;

final class WecomIdentity {

	private WecomIdentity() {
	}

	static String caller(String wecomUserId, String gitlabUsername, String ldapGitlabUsername) {
		if (present(gitlabUsername)) {
			return gitlabUsername.strip();
		}
		if (present(ldapGitlabUsername)) {
			return ldapGitlabUsername.strip();
		}
		return wecomUserId == null ? "" : wecomUserId.strip();
	}

	static long externalId(String wecomUserId) {
		long hash = wecomUserId.hashCode() & 0xffffffffL;
		return hash == 0 ? -1L : -hash;
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
