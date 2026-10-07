package dev.helix.gitlab;

public final class IssueBodyText {

	private IssueBodyText() {
	}

	public static String prose(String description) {
		if (description == null || description.isBlank()) {
			return "";
		}
		String stripped = description.replaceAll("(?s)<!--\\s*helix\\s*-->.*?<!--\\s*/helix\\s*-->", "");
		return stripped.trim();
	}

}
