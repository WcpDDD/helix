package dev.helix.gitlab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueBodyTextTest {

	@Test
	void proseDropsTheHelixSection() {
		String description = """
				开头。

				<!-- helix -->
				状态：实现中
				<!-- /helix -->

				结尾。
				""";

		String prose = IssueBodyText.prose(description);
		assertThat(prose).contains("开头。").contains("结尾。");
		assertThat(prose).doesNotContain("状态").doesNotContain("helix");
	}

	@Test
	void proseKeepsTheWholeDescriptionWhenThereIsNoMarker() {
		assertThat(IssueBodyText.prose("只有正文")).isEqualTo("只有正文");
	}

	@Test
	void blankDescriptionIsEmpty() {
		assertThat(IssueBodyText.prose(null)).isEmpty();
		assertThat(IssueBodyText.prose("  ")).isEmpty();
		assertThat(IssueBodyText.prose("<!-- helix -->\n状态\n<!-- /helix -->")).isEmpty();
	}

}
