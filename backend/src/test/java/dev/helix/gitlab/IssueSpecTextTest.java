package dev.helix.gitlab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueSpecTextTest {

	@Test
	void readsBranchAndSpecPathsFromTheHelixBlock() {
		IssueSpecText.SpecLinks links = IssueSpecText.links("""
				正文留在外面。
				规格：openspec/not-this.md

				<!-- helix -->
				状态：提案中
				阶段：提案
				分支：main
				规格：openspec/changes/helix-spec-reader
				规格：./openspec/changes/helix-spec-reader/proposal.md
				<!-- /helix -->
				""");

		assertThat(links.branch()).isEqualTo("main");
		assertThat(links.paths()).containsExactly("openspec/changes/helix-spec-reader",
				"openspec/changes/helix-spec-reader/proposal.md");
	}

	@Test
	void rejectsAPathThatLeavesTheRepository() {
		assertThatThrownBy(() -> IssueSpecText.links("""
				<!-- helix -->
				规格：../secrets.md
				<!-- /helix -->
				""")).isInstanceOf(IllegalArgumentException.class);
	}

}
