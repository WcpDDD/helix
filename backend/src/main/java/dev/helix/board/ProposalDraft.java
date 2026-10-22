package dev.helix.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.helix.gitlab.IssueStatusText;

/**
 * 创建任务时要收的字段，和提案规范的类型清单一致。
 */
public final class ProposalDraft {

	/** 交给当前 manager 托管。旧的 ai/* 角色和状态标签仍由原调度使用。 */
	public static final String AI = "ai/managed";

	/** 待办已经派给人。只有 manager 可以打上。 */
	public static final String HITL = "ai/hitl";

	private static final List<String> BUG = List.of("复现步骤", "期望行为", "实际行为", "环境", "影响面");

	private static final List<String> TECH = List.of("背景", "备选方案", "决策框架", "取舍", "影响面", "验证方式");

	private static final List<String> FEATURE = List.of("背景", "核心用户故事", "业务交互流程", "做什么", "怎么做", "预期效果", "交互边界");

	private ProposalDraft() {
	}

	public static List<String> fields(String kind) {
		if ("bug".equals(kind)) {
			return BUG;
		}
		if ("tech".equals(kind)) {
			return TECH;
		}
		if ("feature".equals(kind)) {
			return FEATURE;
		}
		return null;
	}

	public static String typeLabel(String kind) {
		if ("bug".equals(kind)) {
			return "type/bug";
		}
		if ("tech".equals(kind)) {
			return "type/tech";
		}
		if ("feature".equals(kind)) {
			return "type/feature";
		}
		return null;
	}

	public static List<String> missing(String kind, Map<String, String> fields) {
		List<String> names = fields(kind);
		List<String> missing = new ArrayList<>();
		if (names == null) {
			return missing;
		}
		Map<String, String> given = fields == null ? Map.of() : fields;
		for (String name : names) {
			String value = given.get(name);
			if (value == null || value.isBlank()) {
				missing.add(name);
			}
		}
		return missing;
	}

	public static String description(String kind, Map<String, String> fields, List<String> attachments) {
		StringBuilder body = new StringBuilder();
		Map<String, String> given = fields == null ? Map.of() : fields;
		for (String name : fields(kind)) {
			if (!body.isEmpty()) {
				body.append("\n\n");
			}
			body.append("### ").append(name).append("\n\n");
			body.append(given.get(name).strip());
		}
		if (attachments != null) {
			boolean heading = false;
			for (String markdown : attachments) {
				if (markdown == null || markdown.isBlank()) {
					continue;
				}
				if (!heading) {
					if (!body.isEmpty()) {
						body.append("\n\n");
					}
					body.append("### 参考附件");
					heading = true;
				}
				body.append("\n\n").append(markdown.strip());
			}
		}
		return IssueStatusText.withStatus(body.toString(), "提案中", "提案");
	}

	public static List<String> labels(String kind) {
		List<String> labels = new ArrayList<>();
		labels.add("status/proposal");
		labels.add(typeLabel(kind));
		return List.copyOf(labels);
	}

}
