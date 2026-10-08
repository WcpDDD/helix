package dev.helix.gitlab;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IssueSpecText {

	private static final Pattern BLOCK = Pattern.compile("(?s)<!--\\s*helix\\s*-->(.*?)<!--\\s*/helix\\s*-->");

	private IssueSpecText() {
	}

	public static SpecLinks links(String description) {
		Matcher matcher = BLOCK.matcher(description == null ? "" : description);
		if (!matcher.find()) {
			return new SpecLinks(null, List.of());
		}
		String branch = null;
		Set<String> paths = new LinkedHashSet<>();
		for (String line : matcher.group(1).split("\n")) {
			String stripped = line.strip();
			String branchValue = value(stripped, "分支");
			if (branchValue != null) {
				branch = branchName(branchValue);
				continue;
			}
			String pathValue = value(stripped, "规格");
			if (pathValue != null) {
				paths.add(path(pathValue));
			}
		}
		return new SpecLinks(branch, List.copyOf(paths));
	}

	private static String value(String line, String label) {
		String prefix = label + "：";
		String ascii = label + ":";
		if (line.startsWith(prefix)) {
			return line.substring(prefix.length()).strip();
		}
		if (line.startsWith(ascii)) {
			return line.substring(ascii.length()).strip();
		}
		return null;
	}

	private static String branchName(String raw) {
		if (raw.isEmpty() || raw.contains("..") || raw.contains("\\") || raw.contains(" ")) {
			throw new IllegalArgumentException("分支名不能用");
		}
		return raw;
	}

	private static String path(String raw) {
		String text = raw.strip();
		if (text.startsWith("./")) {
			text = text.substring(2);
		}
		Path normalized = Path.of(text).normalize();
		if (text.isEmpty() || text.contains("\\") || text.contains("://") || normalized.isAbsolute()
				|| normalized.startsWith("..")) {
			throw new IllegalArgumentException("规格路径要相对仓库根");
		}
		return normalized.toString().replace('\\', '/');
	}

	public record SpecLinks(String branch, List<String> paths) {
	}

}
