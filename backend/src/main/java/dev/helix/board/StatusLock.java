package dev.helix.board;

import java.util.regex.Pattern;

import dev.helix.gitlab.IssueStatusText;

/**
 * 写回 issue 时保住原来的状态。谁都不许改。
 */
public final class StatusLock {

	private static final Pattern BLOCK = Pattern.compile("(?s)<!--\\s*helix\\s*-->.*?<!--\\s*/helix\\s*-->");

	private StatusLock() {
	}

	public static String keep(String original, String rewritten) {
		String status = IssueStatusText.statusOf(original);
		String stripped = strip(rewritten);
		if (status == null) {
			return stripped;
		}
		String stage = IssueStatusText.stageOf(original);
		if (stage == null) {
			stage = IssueStatusText.stageFor(status, "");
		}
		return IssueStatusText.withStatus(stripped, status, stage);
	}

	private static String strip(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		return BLOCK.matcher(text).replaceAll("").strip();
	}

}
