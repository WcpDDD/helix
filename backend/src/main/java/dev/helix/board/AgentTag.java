package dev.helix.board;

import java.util.List;

public final class AgentTag {

	public static final List<String> ALL = List.of(
			"manager",
			"arch",
			"proposal",
			"proposal-review",
			"developer",
			"reviewer",
			"tester");

	private AgentTag() {
	}

	public static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String tag = raw.strip();
		if (!ALL.contains(tag)) {
			throw new IllegalArgumentException();
		}
		return tag;
	}

}
