package dev.helix.board;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	void keepsCreatorAndOwnersOnTheTask() {
		String original = """
				# 说明
				version: 1
				tasks:
				  - main: 21
				    children:
				      - 22
				""";

		String written = BoardFile.withPeople(original, 21, "陈新培", List.of("甲", "a:b"));
		assertThat(written).startsWith("# 说明\n");
		assertThat(written).contains("    children:\n      - 22\n");
		BoardFile board = BoardFile.parse(written);
		assertThat(board.authorOf(21)).isEqualTo("陈新培");
		assertThat(board.ownersOf(21)).containsExactly("甲", "a:b");
		assertThat(board.childrenOf(21)).containsExactly(22);

		String replaced = BoardFile.withPeople(written, 21, null, List.of("乙"));
		BoardFile updated = BoardFile.parse(replaced);
		assertThat(updated.authorOf(21)).isEqualTo("陈新培");
		assertThat(updated.ownersOf(21)).containsExactly("乙");
		assertThat(replaced).contains("    children:\n      - 22\n");

		String cleared = BoardFile.withPeople(replaced, 21, null, List.of());
		BoardFile emptyOwners = BoardFile.parse(cleared);
		assertThat(emptyOwners.authorOf(21)).isEqualTo("陈新培");
		assertThat(emptyOwners.ownersOf(21)).isEmpty();
		assertThat(emptyOwners.childrenOf(21)).containsExactly(22);

		String added = BoardFile.withPeople(cleared, 40, "王五", null);
		BoardFile both = BoardFile.parse(added);
		assertThat(both.authorOf(40)).isEqualTo("王五");
		assertThat(both.ownersOf(40)).isEmpty();
		assertThat(both.childrenOf(21)).containsExactly(22);
	}

	@Test
	void writingPeopleKeepsTheRestOfTheBoard() throws Exception {
		String yaml = Files.readString(Path.of("src/test/resources/board/board.yaml"));
		BoardFile before = BoardFile.parse(yaml);

		String next = BoardFile.withPeople(yaml, 12, "陈新培", List.of("甲", "乙"));

		assertThat(next).startsWith("# 任务和 issue 的对应");
		BoardFile after = BoardFile.parse(next);
		assertThat(after.mains()).containsExactlyElementsOf(before.mains());
		for (int main : before.mains()) {
			assertThat(after.childrenOf(main)).containsExactlyElementsOf(before.childrenOf(main));
			assertThat(after.edgesOf(main)).containsExactlyElementsOf(before.edgesOf(main));
		}
		assertThat(after.authorOf(12)).isEqualTo("陈新培");
		assertThat(after.ownersOf(12)).containsExactly("甲", "乙");
		assertThat(after.authorOf(21)).isEmpty();
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
