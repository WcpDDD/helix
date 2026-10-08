package dev.helix.board;

import java.util.List;

import dev.helix.board.AgentService.AgentDraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

	@Mock
	private AgentRepository agents;

	@Test
	void storesTheAgentFileAndTheSkillsItCites() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(agents.existsByProject_IdAndName(null, "review")).thenReturn(false);
		when(agents.save(any())).thenAnswer(invocation -> {
			AgentEntity agent = invocation.getArgument(0);
			var id = AgentEntity.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(agent, 7L);
			return agent;
		});

		var savedView = service.create(project, new AgentDraft(" review ", "按引用的 skill 审阅。", List.of(
				"skills/backend-review/SKILL.md",
				"skills/backend-review/SKILL.md",
				"./skills/dev-flow/SKILL.md")));

		assertThat(savedView.id()).isEqualTo(7L);
		assertThat(savedView.name()).isEqualTo("review");
		assertThat(savedView.body()).isEqualTo("按引用的 skill 审阅。");
		assertThat(savedView.skills()).containsExactly("skills/backend-review/SKILL.md", "skills/dev-flow/SKILL.md");
	}

	@Test
	void rejectsASkillOutsideTheDirectory() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");

		assertThatThrownBy(() -> service.create(project,
				new AgentDraft("review", "", List.of("openspec/changes/helix-spec-reader/proposal.md"))))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void rejectsADuplicateName() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(agents.existsByProject_IdAndName(null, "review")).thenReturn(true);

		assertThatThrownBy(() -> service.create(project, new AgentDraft("review", "", List.of())))
				.isInstanceOf(ResponseStatusException.class);
	}

}
