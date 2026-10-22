package dev.helix.board;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import dev.helix.gitlab.IssueStatusText;

/**
 * 一轮只读 issue 和评论，决定派谁。状态保持不动。
 */
public final class ManagerRound {

	private ManagerRound() {
	}

	public static Plan plan(String status, String description, List<String> labels, List<Note> notes, List<Face> agents) {
		String state = status == null || status.isBlank() ? "提案中" : status;
		if (terminal(state)) {
			return stop(state + " 已结束，不再派发。");
		}
		if ("提案中".equals(state)) {
			return stage(description, labels, notes, agents, state, "proposal", "proposal-review", "已写回",
					"提案审阅已通过，可以进入实现中。");
		}
		if ("实现中".equals(state)) {
			return stage(description, labels, notes, agents, state, "developer", "reviewer", "已实现",
					"实现已写在 issue 上，可以进入审阅。");
		}
		if ("审阅中".equals(state)) {
			return stage(description, labels, notes, agents, state, "developer", "reviewer", "已实现",
					"审阅已通过，可以进入可上线。");
		}
		if ("可上线".equals(state)) {
			return stage(description, labels, notes, agents, state, "developer", "tester", "已实现",
					"测试已通过，可以进入待上线池。");
		}
		if ("待上线池".equals(state)) {
			return stage(description, labels, notes, agents, state, "tester", "", "通过", "可以进入已上线。");
		}
		return stop("这个状态没有要派的角色。");
	}

	public static String shown(String description) {
		String found = IssueStatusText.statusOf(description);
		return found == null ? "提案中" : found;
	}

	public static String stamp(String text, String status, List<Note> notes) {
		StringBuilder line = new StringBuilder(text).append(" @").append(status);
		if (notes != null) {
			for (Note note : notes) {
				line.append(" #").append(note.id());
			}
		}
		return line.toString();
	}

	public static boolean cites(String line, long id) {
		if (line == null) {
			return false;
		}
		return Pattern.compile("(?<!\\d)#" + id + "(?!\\d)").matcher(line).find();
	}

	private static Plan stage(String description, List<String> labels, List<Note> notes, List<Face> agents, String status,
			String worker, String reviewer, String donePrefix, String readyNote) {
		if (find(agents, worker) == null) {
			return stop("没有配置 " + worker + "。");
		}
		String expected = expected(description, labels, notes, status, worker);
		String workerLine = IssueBoard.line(description, worker);
		boolean produced = workerLine.startsWith(donePrefix) && workerLine.contains("@" + status);
		String review = reviewer == null || reviewer.isBlank() ? "" : IssueBoard.line(description, reviewer);
		if (reviewer != null && !reviewer.isBlank()) {
			if (find(agents, reviewer) == null) {
				return stop("没有配置 " + reviewer + "。");
			}
			if (openRejection(description, notes)) {
				return dispatch(reviewer, "评论里有打回，交给 " + reviewer + "。");
			}
		}
		if (!produced) {
			if (workerLine.equals(expected)) {
				return stop(workerLine);
			}
			return dispatch(worker, "这一步还没按现在的内容和评论写回 issue。");
		}
		if (reviewer == null || reviewer.isBlank()) {
			if (!workerLine.equals(expected)) {
				return dispatch(worker, "有新的评论还没写回 issue。");
			}
			return ready(readyNote);
		}
		if (!workerLine.equals(expected) || unanswered(review, workerLine, status)) {
			return dispatch(worker, "打回还没在 issue 上改完。");
		}
		if (!passed(review, status, notes)) {
			return dispatch(reviewer, "审阅还没按现在的内容写回 issue。");
		}
		return ready(readyNote);
	}

	public static String expected(String description, List<String> labels, List<Note> notes, String status, String worker) {
		if ("proposal".equals(worker)) {
			String note = MockAgent.proposalNote(description, labels);
			return stamp("已写回".equals(note) ? "已写回" : note, status, notes);
		}
		if ("developer".equals(worker)) {
			return stamp("已实现。改动记在这张 issue 上。", status, notes);
		}
		return stamp("通过", status, notes);
	}

	private static boolean openRejection(String description, List<Note> notes) {
		if (notes == null) {
			return false;
		}
		for (Note note : notes) {
			if (note.body() != null && note.body().contains("打回") && !cites(description, note.id())) {
				return true;
			}
		}
		return false;
	}

	private static boolean unanswered(String review, String workerLine, String status) {
		if (review == null || !review.startsWith("打回") || !review.contains("@" + status)) {
			return false;
		}
		var ids = Pattern.compile("#(\\d+)").matcher(review);
		boolean found = false;
		while (ids.find()) {
			found = true;
			if (!cites(workerLine, Long.parseLong(ids.group(1)))) {
				return true;
			}
		}
		return !found && !workerLine.contains("@" + status);
	}

	private static boolean passed(String review, String status, List<Note> notes) {
		return review.equals(stamp("通过", status, notes));
	}

	private static boolean terminal(String status) {
		return "已上线".equals(status) || "已关闭".equals(status) || "已驳回".equals(status);
	}

	private static Plan dispatch(String tag, String note) {
		return new Plan("dispatch", tag, note);
	}

	private static Plan stop(String note) {
		return new Plan("stop", "", note);
	}

	private static Plan ready(String note) {
		return new Plan("ready", "", note);
	}

	private static Face find(List<Face> agents, String tag) {
		if (agents == null) {
			return null;
		}
		for (Face agent : agents) {
			if (tag.equals(agent.tag())) {
				return agent;
			}
		}
		return null;
	}

	public record Face(String tag, String name, String body, List<String> skills) {
	}

	public record Note(long id, String body) {
	}

	public record Plan(String action, String tag, String note) {
	}

	public static String kindOf(List<String> labels) {
		if (labels == null) {
			return "";
		}
		for (String label : labels) {
			if ("type/bug".equals(label)) {
				return "bug";
			}
			if ("type/tech".equals(label)) {
				return "tech";
			}
			if ("type/feature".equals(label)) {
				return "feature";
			}
		}
		return "";
	}

	public static Map<String, String> sections(String description) {
		Map<String, String> fields = new java.util.LinkedHashMap<>();
		String current = null;
		StringBuilder body = new StringBuilder();
		String source = description == null ? "" : description;
		source = source.replaceAll("(?s)<!--\\s*helix-board\\s*-->.*?<!--\\s*/helix-board\\s*-->", "");
		source = source.replaceAll("(?s)<!--\\s*helix\\s*-->.*?<!--\\s*/helix\\s*-->", "");
		for (String line : source.split("\n", -1)) {
			if (line.startsWith("### ")) {
				if (current != null) {
					fields.put(current, body.toString().strip());
				}
				current = line.substring(4).strip();
				body.setLength(0);
			}
			else if (current != null) {
				body.append(line).append('\n');
			}
		}
		if (current != null) {
			fields.put(current, body.toString().strip());
		}
		return fields;
	}

}
