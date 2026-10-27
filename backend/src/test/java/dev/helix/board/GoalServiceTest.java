package dev.helix.board;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import dev.helix.worker.AgentPresence;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.OpenedIssue;
import dev.helix.worker.AgentSessionService;
import dev.helix.worker.AgentSessionService.IssueAgentView;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalServiceTest {

	@Test
	void startingAGoalLabelsTheIssueAndWakesTheManager() {
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		AgentRepository repository = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = project();
		AgentEntity manager = new AgentEntity(project, "manager", "你只调度。");
		manager.retag("manager");
		setId(manager, 8L);
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace"))
				.thenReturn(List.of(new OpenedIssue(235, List.of("type/feature"))))
				.thenReturn(List.of(new OpenedIssue(235, List.of("type/feature", "ai/managed"))));
		when(repository.findWithSkillsByTag(3L, "manager")).thenReturn(java.util.Optional.of(manager));
		when(sessions.create(3L, 235, 8L)).thenReturn(new IssueAgentView(8L, "manager", "manager", true, true, "worker-local", "本机"));
		GoalService goals = goals(gitlab, repository, sessions);

		GoalService.GoalView view = goals.start("tok", project, 235, "做到测试通过");

		verify(gitlab).addLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 235, "ai/managed");
		verify(sessions).say(eq(3L), eq(235), eq(8L), contains("目标：做到测试通过"));
		assertThat(view.phase()).isEqualTo("turn");
	}

	@Test
	void confirmingHandsTheDecisionBackToTheManager() {
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		AgentRepository repository = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = project();
		AgentEntity manager = new AgentEntity(project, "manager", "你只调度。");
		manager.retag("manager");
		setId(manager, 8L);
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace"))
				.thenReturn(List.of(new OpenedIssue(235, List.of("ai/hitl"))))
				.thenReturn(List.of(new OpenedIssue(235, List.of("ai/managed"))));
		when(repository.findWithSkillsByTag(3L, "manager")).thenReturn(java.util.Optional.of(manager));
		when(sessions.create(3L, 235, 8L)).thenReturn(new IssueAgentView(8L, "manager", "manager", true, true, "worker-local", "本机"));
		GoalService goals = goals(gitlab, repository, sessions);

		GoalService.GoalView view = goals.confirm("tok", project, 235, "按提案继续");

		verify(gitlab).removeLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 235, "ai/hitl");
		verify(gitlab).addLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 235, "ai/managed");
		verify(sessions).say(eq(3L), eq(235), eq(8L), contains("人已确认：按提案继续"));
		assertThat(view.phase()).isEqualTo("turn");
	}

	@Test
	void releasingDropsTheHumanLabelWithoutWakingTheManager() {
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		when(gitlab.opened(anyString(), anyString(), anyString(), anyString()))
				.thenReturn(List.of(new OpenedIssue(235, List.of("type/feature"))));
		GoalService goals = goals(gitlab, mock(AgentRepository.class), sessions);

		GoalService.GoalView view = goals.release("tok", project(), 235);

		verify(gitlab).removeLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 235, "ai/hitl");
		verify(sessions, org.mockito.Mockito.never()).say(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyLong(), anyString());
		assertThat(view.phase()).isEqualTo("idle");
	}

	@Test
	void aHumanTodoShowsAsWaitingForConfirmation() {
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		when(gitlab.opened(anyString(), anyString(), anyString(), anyString()))
				.thenReturn(List.of(new OpenedIssue(235, List.of("ai/managed", "ai/hitl"))));
		GoalService goals = goals(gitlab, mock(AgentRepository.class), mock(AgentSessionService.class));

		GoalService.GoalView view = goals.status("tok", project(), 235);

		assertThat(view.phase()).isEqualTo("confirm");
		assertThat(view.note()).isEqualTo("交给你确认。");
	}

	private static GoalService goals(GitlabIssueClient gitlab, AgentRepository repository, AgentSessionService sessions) {
		ScheduleLog journal = new ScheduleLog(Path.of("target", "goal-" + System.nanoTime()), ScheduleLog.MAX_BYTES);
		AgentPresence presence = new AgentPresence(Duration.ofSeconds(15));
		ManagerAnalysis analysis = new ManagerAnalysis(repository, sessions, new MockRuntime(), presence, journal);
		return new GoalService(gitlab, analysis, new MockRuntime(), presence);
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

}
