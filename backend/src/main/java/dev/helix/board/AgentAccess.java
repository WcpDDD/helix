package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

public final class AgentAccess {

	public static final List<String> ROLES = List.of("reporter", "developer", "maintainer");

	private AgentAccess() {
	}

	public static String fromLevel(int level) {
		if (level >= 40) {
			return "maintainer";
		}
		if (level >= 30) {
			return "developer";
		}
		if (level >= 20) {
			return "reporter";
		}
		if (level >= 10) {
			return "guest";
		}
		return "";
	}

	public static String scope(String raw, String tag) {
		if ("global".equals(raw) || "task".equals(raw)) {
			return raw;
		}
		return tag != null && !tag.isBlank() ? "task" : "global";
	}

	public static List<String> access(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return ROLES;
		}
		List<String> kept = new ArrayList<>();
		for (String role : ROLES) {
			if (raw.stream().anyMatch(item -> role.equals(item == null ? "" : item.strip()))) {
				kept.add(role);
			}
		}
		return kept.isEmpty() ? ROLES : List.copyOf(kept);
	}

	public static boolean seesCatalog(String memberAccess) {
		return memberAccess == null || memberAccess.isBlank() || "maintainer".equals(memberAccess);
	}

	public static boolean allows(String memberAccess, List<String> granted) {
		if (memberAccess == null || memberAccess.isBlank()) {
			return true;
		}
		if ("guest".equals(memberAccess)) {
			return false;
		}
		List<String> open = granted == null || granted.isEmpty() ? ROLES : granted;
		return open.contains(memberAccess);
	}

}
