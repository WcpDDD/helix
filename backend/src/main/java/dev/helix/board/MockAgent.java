package dev.helix.board;

import java.util.List;

/**
 * 按库里的角色把这一步的结论写回 issue。不改状态，也不调用真正的执行端。
 */
public final class MockAgent {

	private MockAgent() {
	}

	public static String proposalNote(String description, List<String> labels) {
		String kind = ManagerRound.kindOf(labels);
		if (ProposalDraft.fields(kind) == null) {
			return "类型不对。";
		}
		List<String> missing = ProposalDraft.missing(kind, ManagerRound.sections(description));
		if (!missing.isEmpty()) {
			return "还要写：" + String.join("、", missing) + "。";
		}
		return "已写回";
	}

	public static Turn run(ManagerRound.Face agent, String description, List<String> labels, String status,
			List<ManagerRound.Note> notes) {
		List<String> skills = agent.skills() == null ? List.of() : agent.skills();
		String tag = agent.tag();
		if ("proposal".equals(tag)) {
			return proposal(skills, description, labels, status, notes);
		}
		if ("developer".equals(tag)) {
			String text = ManagerRound.stamp("已实现。改动记在这张 issue 上。", status, notes);
			return kept(description, tag, skills, text);
		}
		if ("tester".equals(tag) && "待上线池".equals(status)) {
			String text = ManagerRound.stamp("通过", status, notes);
			return kept(description, tag, skills, text);
		}
		if ("proposal-review".equals(tag) || "reviewer".equals(tag) || "tester".equals(tag)) {
			String worker = "proposal-review".equals(tag) ? "proposal" : "developer";
			String text = verdict(description, worker, status, notes);
			return kept(description, tag, skills, text);
		}
		String text = ManagerRound.stamp("你是 " + tag + "。", status, notes);
		return kept(description, tag, skills, text);
	}

	private static String verdict(String description, String worker, String status, List<ManagerRound.Note> notes) {
		String workerLine = IssueBoard.line(description, worker);
		if (notes != null) {
			for (ManagerRound.Note note : notes) {
				if (note.body() != null && note.body().contains("打回") && !ManagerRound.cites(description, note.id())) {
					String excerpt = note.body().strip().replace('\n', ' ');
					if (excerpt.length() > 40) {
						excerpt = excerpt.substring(0, 40);
					}
					return "打回 @" + status + " #" + note.id() + "：" + excerpt;
				}
			}
		}
		String done = "proposal".equals(worker) ? "已写回" : "已实现";
		if (!(workerLine.startsWith(done) && workerLine.contains("@" + status))) {
			return "打回 @" + status + "：还没有写到 issue 上。";
		}
		return ManagerRound.stamp("通过", status, notes);
	}

	private static Turn proposal(List<String> skills, String description, List<String> labels, String status,
			List<ManagerRound.Note> notes) {
		String note = proposalNote(description, labels);
		String text = ManagerRound.stamp("已写回".equals(note) ? "已写回" : note, status, notes);
		String base = description;
		if ("已写回".equals(note)) {
			base = ProposalDraft.description(ManagerRound.kindOf(labels), ManagerRound.sections(description), List.of());
		}
		return kept(description, "proposal", skills, text, base);
	}

	private static Turn kept(String original, String tag, List<String> skills, String text) {
		return kept(original, tag, skills, text, original);
	}

	private static Turn kept(String original, String tag, List<String> skills, String text, String base) {
		String written = StatusLock.keep(original, IssueBoard.put(base, tag, text));
		return new Turn(tag, skills, text, written);
	}

	public record Turn(String tag, List<String> skills, String text, String description) {
	}

}
