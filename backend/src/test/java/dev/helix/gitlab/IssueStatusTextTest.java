package dev.helix.gitlab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueStatusTextTest {

	@Test
	void readsStatusInsideTheSection() {
		String description = "开头\n<!-- helix -->\n状态：实现中\n阶段：实现\n<!-- /helix -->\n结尾";

		assertThat(IssueStatusText.statusOf(description)).isEqualTo("实现中");
		assertThat(IssueStatusText.stageOf(description)).isEqualTo("实现");
	}

	@Test
	void ignoresStatusOutsideTheSection() {
		assertThat(IssueStatusText.statusOf("状态：已上线")).isNull();
	}

	@Test
	void appendsASectionWithoutTouchingTheProse() {
		String next = IssueStatusText.withStatus("开头正文", "审阅中", "实现");

		assertThat(next).startsWith("开头正文\n\n<!-- helix -->\n状态：审阅中\n阶段：实现\n<!-- /helix -->");
		assertThat(IssueStatusText.statusOf(next)).isEqualTo("审阅中");
	}

	@Test
	void replacesStatusAndKeepsTheOtherLines() {
		String description = "开头\n<!-- helix -->\n状态：实现中\n阶段：实现\n结论：先这样\n<!-- /helix -->\n结尾";

		String next = IssueStatusText.withStatus(description, "可上线", "上线");

		assertThat(next).contains("开头").contains("结尾").contains("结论：先这样");
		assertThat(next).contains("状态：可上线").contains("阶段：上线");
		assertThat(next).doesNotContain("状态：实现中");
	}

}
