package dev.helix.board;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalDraftTest {

	@Test
	void featureKeepsTheOriginalTextAndOmitsTheAiLabel() {
		Map<String, String> fields = Map.of(
				"背景", "现在找不到入口",
				"核心用户故事", "管理员要在列表里创建任务",
				"业务交互流程", "打开列表，填写，保存",
				"做什么", "创建任务",
				"怎么做", "表单写入 issue",
				"预期效果", "列表里出现这张 issue",
				"交互边界", "不做扫描");

		String description = ProposalDraft.description("feature", fields, java.util.List.of("![草图](/uploads/a/sketch.png)"));

		assertThat(description).contains("### 核心用户故事").contains("管理员要在列表里创建任务");
		assertThat(description).doesNotContain("交付物");
		assertThat(description).contains("### 参考附件").contains("![草图](/uploads/a/sketch.png)");
		assertThat(description).contains("状态：提案中").contains("阶段：提案");
		assertThat(ProposalDraft.fields("feature")).doesNotContain("交付物");
		assertThat(ProposalDraft.labels("feature")).containsExactly("status/proposal", "type/feature");
		assertThat(ProposalDraft.missing("feature", fields)).isEmpty();
	}

	@Test
	void labelsStayWithTheIssueType() {
		assertThat(ProposalDraft.labels("bug")).containsExactly("status/proposal", "type/bug");
		assertThat(ProposalDraft.labels("tech")).containsExactly("status/proposal", "type/tech");
		assertThat(ProposalDraft.missing("tech", Map.of("背景", "升级"))).contains("备选方案", "决策框架", "取舍");
	}

}
