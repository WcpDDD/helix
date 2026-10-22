package dev.helix.board;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * issue 描述里的协作记录。agent 把结论写在这里，manager 只读这里。
 */
public final class IssueBoard {

	private static final Pattern BLOCK = Pattern.compile("(?s)<!--\\s*helix-board\\s*-->(.*?)<!--\\s*/helix-board\\s*-->");

	private static final List<String> ORDER = List.of("proposal", "proposal-review", "developer", "reviewer", "tester");

	private IssueBoard() {
	}

	public static String line(String description, String tag) {
		for (String raw : body(description).split("\n", -1)) {
			String text = raw.strip();
			String prefix = tag + ": ";
			if (text.startsWith(prefix)) {
				return text.substring(prefix.length()).strip();
			}
		}
		return "";
	}

	public static String put(String description, String tag, String text) {
		Map<String, String> lines = read(description);
		lines.put(tag, text == null ? "" : text.strip());
		return render(description, lines);
	}

	public static String remove(String description, String... tags) {
		Map<String, String> lines = read(description);
		for (String tag : tags) {
			lines.remove(tag);
		}
		return render(description, lines);
	}

	private static Map<String, String> read(String description) {
		Map<String, String> lines = new LinkedHashMap<>();
		for (String raw : body(description).split("\n", -1)) {
			String text = raw.strip();
			int split = text.indexOf(": ");
			if (split <= 0) {
				continue;
			}
			lines.put(text.substring(0, split), text.substring(split + 2).strip());
		}
		return lines;
	}

	private static String body(String description) {
		if (description == null || description.isBlank()) {
			return "";
		}
		Matcher matcher = BLOCK.matcher(description);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String render(String description, Map<String, String> lines) {
		String rest = description == null ? "" : BLOCK.matcher(description).replaceAll("").strip();
		if (lines.isEmpty()) {
			return rest;
		}
		StringBuilder block = new StringBuilder("<!-- helix-board -->\n");
		for (String tag : ORDER) {
			if (lines.containsKey(tag)) {
				block.append(tag).append(": ").append(lines.remove(tag)).append('\n');
			}
		}
		for (Map.Entry<String, String> entry : lines.entrySet()) {
			block.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
		}
		block.append("<!-- /helix-board -->");
		if (rest.isEmpty()) {
			return block.toString();
		}
		return block + "\n\n" + rest;
	}

}
