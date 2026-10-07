package dev.helix.gitlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IssueStatusText {

	public static final Set<String> STATUSES = Set.of("提案中", "已驳回", "实现中", "审阅中", "可上线", "待上线池", "已上线", "已关闭");

	private static final Set<String> STAGES = Set.of("提案", "实现", "上线");

	private static final Pattern BLOCK = Pattern.compile("(?s)<!--\\s*helix\\s*-->(.*?)<!--\\s*/helix\\s*-->");

	private IssueStatusText() {
	}

	public static String statusOf(String description) {
		return value(description, "状态");
	}

	public static String stageOf(String description) {
		String stage = value(description, "阶段");
		return stage != null && STAGES.contains(stage) ? stage : null;
	}

	public static String stageFor(String status, String current) {
		if ("已关闭".equals(status)) {
			return current == null || current.isBlank() ? "提案" : current;
		}
		if ("提案中".equals(status) || "已驳回".equals(status)) {
			return "提案";
		}
		if ("实现中".equals(status) || "审阅中".equals(status)) {
			return "实现";
		}
		return "上线";
	}

	public static String withStatus(String description, String status, String stage) {
		String source = description == null ? "" : description;
		Matcher matcher = BLOCK.matcher(source);
		String block = "<!-- helix -->\n状态：" + status + "\n阶段：" + stage + "\n<!-- /helix -->";
		if (!matcher.find()) {
			if (source.isBlank()) {
				return block + "\n";
			}
			return source.stripTrailing() + "\n\n" + block + "\n";
		}
		String inner = rewrite(matcher.group(1), status, stage);
		return source.substring(0, matcher.start()) + "<!-- helix -->\n" + inner + "<!-- /helix -->"
				+ source.substring(matcher.end());
	}

	private static String rewrite(String inner, String status, String stage) {
		List<String> rest = new ArrayList<>();
		for (String line : inner.split("\n", -1)) {
			String stripped = line.strip();
			if (stripped.startsWith("状态：") || stripped.startsWith("状态:") || stripped.startsWith("阶段：")
					|| stripped.startsWith("阶段:")) {
				continue;
			}
			rest.add(line);
		}
		while (!rest.isEmpty() && rest.get(0).isBlank()) {
			rest.remove(0);
		}
		while (!rest.isEmpty() && rest.get(rest.size() - 1).isBlank()) {
			rest.remove(rest.size() - 1);
		}
		StringBuilder body = new StringBuilder();
		body.append("状态：").append(status).append('\n');
		body.append("阶段：").append(stage).append('\n');
		if (!rest.isEmpty()) {
			body.append(String.join("\n", rest)).append('\n');
		}
		return body.toString();
	}

	private static String value(String description, String label) {
		Matcher matcher = BLOCK.matcher(description == null ? "" : description);
		if (!matcher.find()) {
			return null;
		}
		for (String line : matcher.group(1).split("\n")) {
			String stripped = line.strip();
			String prefix = label + "：";
			String ascii = label + ":";
			String raw = null;
			if (stripped.startsWith(prefix)) {
				raw = stripped.substring(prefix.length()).strip();
			}
			else if (stripped.startsWith(ascii)) {
				raw = stripped.substring(ascii.length()).strip();
			}
			if (raw == null) {
				continue;
			}
			if ("状态".equals(label)) {
				return STATUSES.contains(raw) ? raw : null;
			}
			return raw;
		}
		return null;
	}

}
