package dev.helix.gitlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IssueStatusText {

	public static final Set<String> STATUSES = Set.of("提案中", "已驳回", "实现中", "审阅中", "可上线", "待上线池", "已上线", "已关闭");

	private static final Map<String, String> LABEL_OF = new LinkedHashMap<>();

	static {
		LABEL_OF.put("提案中", "status/proposal");
		LABEL_OF.put("实现中", "status/in-progress");
		LABEL_OF.put("审阅中", "status/review");
		LABEL_OF.put("可上线", "status/releasable");
		LABEL_OF.put("待上线池", "status/pending");
		LABEL_OF.put("已驳回", "status/rejected");
	}

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

	/** 已上线、已关闭没有对应的 status 标签，返回 null，调用方只摘掉旧的 status/*。 */
	public static String labelFor(String status) {
		return LABEL_OF.get(status);
	}

	public static boolean isStatusLabel(String label) {
		return label != null && LABEL_OF.containsValue(label);
	}

	public static List<String> statusLabelsToRemove(List<String> labels, String keep) {
		if (labels == null || labels.isEmpty()) {
			return List.of();
		}
		List<String> remove = new ArrayList<>();
		for (String label : labels) {
			if (isStatusLabel(label) && !label.equals(keep)) {
				remove.add(label);
			}
		}
		return List.copyOf(remove);
	}

	/**
	 * 标签优先。没有 status/* 时再读描述里的 helix 块，用来区分已上线和已关闭。
	 */
	public static String from(List<String> labels, String state, String description) {
		if (labels != null) {
			for (String label : labels) {
				for (Map.Entry<String, String> entry : LABEL_OF.entrySet()) {
					if (entry.getValue().equals(label)) {
						return entry.getKey();
					}
				}
			}
		}
		String written = statusOf(description);
		if (written != null) {
			return written;
		}
		if (state != null && state.equalsIgnoreCase("closed")) {
			return "已关闭";
		}
		return null;
	}

	public static String stageMatching(String description, String status, String current) {
		String written = statusOf(description);
		if (status != null && status.equals(written)) {
			String stage = stageOf(description);
			if (stage != null) {
				return stage;
			}
		}
		if ("已关闭".equals(status)) {
			String declared = stageOf(description);
			if (declared != null) {
				return declared;
			}
			return stageFor(status, current == null ? "" : current);
		}
		return stageFor(status, current == null ? "" : current);
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
