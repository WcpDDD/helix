package dev.helix.board;

import java.nio.file.Path;

import dev.helix.worker.AgentSessionService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalHandoffTest {

	@Test
	void handsTheNewIssueToTheProposalSessionOnTheWorker() {
		AgentRepository agents = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = project();
		AgentEntity proposal = new AgentEntity(project, "proposal", "审字段。");
		proposal.retag("proposal");
		setId(proposal, 8L);
		when(agents.findWithSkillsByTag(3L, "proposal")).thenReturn(java.util.Optional.of(proposal));
		ProposalHandoff handoff = new ProposalHandoff(agents, sessions, journal());

		handoff.hand(project, 321);

		verify(sessions).create(3L, 321, 8L);
		verify(sessions).say(eq(3L), eq(321), eq(8L), contains("按提案规范把提案写回来。"));
	}

	@Test
	void missingProposalIsRejectedBeforeAnyWork() {
		AgentRepository agents = mock(AgentRepository.class);
		when(agents.findWithSkillsByTag(3L, "proposal")).thenReturn(java.util.Optional.empty());
		ProposalHandoff handoff = new ProposalHandoff(agents, mock(AgentSessionService.class), journal());

		assertThatThrownBy(() -> handoff.ensure(project())).isInstanceOf(ResponseStatusException.class);
	}

	private static ProjectEntity project() {
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		setId(project, 3L);
		return project;
	}

	private static void setId(Object target, long id) {
		try {
			var field = target.getClass().getDeclaredField("id");
			field.setAccessible(true);
			field.set(target, id);
		}
		catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static ScheduleLog journal() {
		return new ScheduleLog(Path.of("target", "proposal-hand-" + System.nanoTime()), ScheduleLog.MAX_BYTES);
	}

}
