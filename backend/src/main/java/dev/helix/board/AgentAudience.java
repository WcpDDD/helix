package dev.helix.board;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class AgentAudience {

	private final MemberRepository members;

	public AgentAudience(MemberRepository members) {
		this.members = members;
	}

	public String accessOf(Long projectId, String username) {
		if (projectId == null || username == null || username.isBlank()) {
			return "";
		}
		return members.findByProject_IdAndGitLogin(projectId, username)
				.map(MemberEntity::getAccessLevel)
				.orElse("");
	}

	public boolean allows(Long projectId, String username, List<String> granted) {
		return AgentAccess.allows(accessOf(projectId, username), granted);
	}

	public boolean seesCatalog(Long projectId, String username) {
		return AgentAccess.seesCatalog(accessOf(projectId, username));
	}

}
