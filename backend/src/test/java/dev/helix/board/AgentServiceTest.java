package dev.helix.board;

import java.util.List;
import java.util.Optional;

import dev.helix.board.AgentService.AgentDraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
				"./skills/dev-flow/SKILL.md"), "developer"));

		assertThat(savedView.id()).isEqualTo(7L);
		assertThat(savedView.name()).isEqualTo("review");
		assertThat(savedView.body()).isEqualTo("按引用的 skill 审阅。");
		assertThat(savedView.skills()).containsExactly("skills/backend-review/SKILL.md", "skills/dev-flow/SKILL.md");
		assertThat(savedView.tag()).isEqualTo("developer");
	}

	@Test
	void rejectsASkillOutsideTheDirectory() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");

		assertThatThrownBy(() -> service.create(project,
				new AgentDraft("review", "", List.of("openspec/changes/helix-spec-reader/proposal.md"), null)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void rejectsADuplicateName() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(agents.existsByProject_IdAndName(null, "review")).thenReturn(true);

		assertThatThrownBy(() -> service.create(project, new AgentDraft("review", "", List.of(), null)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void rejectsATagOutsideTheFixedRoles() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");

		assertThatThrownBy(() -> service.create(project, new AgentDraft("review", "", List.of(), "产品")))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void rejectsASecondManagerInTheSameProject() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(agents.existsByProject_IdAndName(null, "lead")).thenReturn(false);
		when(agents.existsByProject_IdAndTag(null, "manager")).thenReturn(true);

		assertThatThrownBy(() -> service.create(project, new AgentDraft("lead", "", List.of(), "manager")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void rejectsASecondDeveloperInTheSameProject() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(agents.existsByProject_IdAndName(null, "impl")).thenReturn(false);
		when(agents.existsByProject_IdAndTag(null, "developer")).thenReturn(true);

		assertThatThrownBy(() -> service.create(project, new AgentDraft("impl", "", List.of(), "developer")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void letsTheCurrentManagerKeepItsRole() {
		AgentService service = new AgentService(agents);
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		AgentEntity agent = new AgentEntity(project, "lead", "");
		setId(agent, 7L);
		when(agents.findByIdAndProject_Id(7L, null)).thenReturn(Optional.of(agent));
		when(agents.existsByProject_IdAndTagAndIdNot(null, "manager", 7L)).thenReturn(false);

		var savedView = service.update(project, 7L, new AgentDraft("lead", "", List.of(), "manager"));

		assertThat(savedView.tag()).isEqualTo("manager");
	}

	private static void setId(AgentEntity agent, long id) {
		try {
			var field = AgentEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(agent, id);
		}
		catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

}
