package dev.helix.board;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillIndexTest {

	@Test
	void groupsEachSkillDirectoryAroundItsSkillFile() {
		List<SkillIndex.SkillView> skills = SkillIndex.fromPaths(List.of(
				"skills/dev-flow/references/state-machine.md",
				"skills/dev-flow/SKILL.md",
				"skills/shared/writing/SKILL.md",
				"skills/shared/writing/visual-companion.md",
				"skills/shared/writing/scripts/helper.py"));

		assertThat(skills).extracting(SkillIndex.SkillView::name).containsExactly("dev-flow",
				"shared/writing");
		assertThat(skills.get(0).files()).containsExactly("skills/dev-flow/SKILL.md",
				"skills/dev-flow/references/state-machine.md");
		assertThat(skills.get(1).files()).containsExactly("skills/shared/writing/SKILL.md",
				"skills/shared/writing/visual-companion.md");
	}

	@Test
	void rejectsAPathOutsideTheSkillDirectory() {
		assertThatThrownBy(() -> SkillIndex.require("../skills/dev-flow/SKILL.md"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SkillIndex.require("openspec/changes/helix-spec-reader/proposal.md"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
