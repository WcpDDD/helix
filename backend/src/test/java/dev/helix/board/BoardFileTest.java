package dev.helix.board;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardFileTest {

	@Test
	void readsMainChildrenAndEdges() {
		BoardFile board = BoardFile.parse("""
				version: 1
				tasks:
				  - main: 202
				    children:
				      - 206
				      - 207
				  - main: 147
				    children:
				      - 149
				      - 150
				    edges:
				      - from: 149
				        to: 150
				        needs_contract: true
				        contract_ref: openspec/changes/example/design.md
				""");

		assertThat(board.isChild(206)).isTrue();
		assertThat(board.isChild(202)).isFalse();
		assertThat(board.isChild(191)).isFalse();
		assertThat(board.childrenOf(202)).containsExactly(206, 207);
		assertThat(board.edgesOf(147)).containsExactly(new BoardFile.Edge(149, 150, true,
				"openspec/changes/example/design.md"));
		assertThat(board.edgesOf(202)).isEmpty();
	}

	@Test
	void rejectsAnIssueListedTwice() {
		assertThatThrownBy(() -> BoardFile.parse("""
				version: 1
				tasks:
				  - main: 12
				    children:
				      - 40
				  - main: 21
				    children:
				      - 40
				""")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAnEdgeThatLeavesTheTask() {
		assertThatThrownBy(() -> BoardFile.parse("""
				version: 1
				tasks:
				  - main: 147
				    children:
				      - 149
				    edges:
				      - from: 149
				        to: 150
				        needs_contract: false
				""")).isInstanceOf(IllegalArgumentException.class);
	}

}
