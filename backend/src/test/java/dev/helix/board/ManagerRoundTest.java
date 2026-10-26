package dev.helix.board;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import dev.helix.worker.AgentPresence;

import dev.helix.gitlab.IssueStatusText;
import dev.helix.worker.AgentSessionService;
import dev.helix.worker.AgentSessionService.IssueAgentView;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagerRoundTest {

	@Test
	void theHeartbeatWakesTheManagerWithAShortPrompt() {
		AgentRepository repository = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		setId(project, 3L);
		AgentEntity manager = new AgentEntity(project, "manager", "你维护这张 issue 的状态，只调度。");
		manager.retag("manager");
		setAgentId(manager, 8L);
		when(repository.findWithSkillsByTag(3L, "manager")).thenReturn(java.util.Optional.of(manager));
		when(sessions.create(3L, 232, 8L)).thenReturn(new IssueAgentView(8L, "manager", "manager", true, true, "worker-local", "本机"));
		ScheduleLog journal = new ScheduleLog(Path.of("target", "manager-ask-" + System.nanoTime()), ScheduleLog.MAX_BYTES);
		ManagerAnalysis analysis = new ManagerAnalysis(repository, sessions, new MockRuntime(), new AgentPresence(Duration.ofSeconds(15)), journal);

		analysis.analyze("tok", project, 232, List.of("ai/managed", "type/feature"));
		analysis.analyze("tok", project, 232, List.of("ai/managed", "type/feature"));

		verify(sessions, times(2)).say(eq(3L), eq(232), eq(8L), contains("做这一轮。"));
		verify(sessions, never()).say(eq(3L), eq(232), eq(8L), contains("描述"));
		verify(sessions, never()).say(eq(3L), eq(232), eq(8L), contains("评论"));
		assertThat(journal.read("schedule.jsonl", 10).records()).filteredOn(row -> "ask".equals(row.get("action"))).hasSize(2);
	}

	@Test
	void aRunningAgentKeepsTheManagerAsleep() {
		AgentRepository repository = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		setId(project, 3L);
		AgentEntity developer = new AgentEntity(project, "developer", "你是 developer。");
		developer.retag("developer");
		setAgentId(developer, 5L);
		MockRuntime runtime = new MockRuntime();
		runtime.hold(3L, 232, developer);
		ScheduleLog journal = new ScheduleLog(Path.of("target", "manager-busy-" + System.nanoTime()), ScheduleLog.MAX_BYTES);
		ManagerAnalysis analysis = new ManagerAnalysis(repository, sessions, runtime, new AgentPresence(Duration.ofSeconds(15)), journal);

		analysis.analyze("tok", project, 232, List.of("ai/managed"));

		verify(sessions, never()).create(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
		verify(sessions, never()).say(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString());
	}

	@Test
	void aReportedRunKeepsTheManagerAsleep() {
		AgentRepository repository = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		setId(project, 3L);
		AgentEntity proposal = new AgentEntity(project, "proposal", "你写提案。");
		proposal.retag("proposal");
		setAgentId(proposal, 2L);
		when(repository.findWithSkillsById(2L, 3L)).thenReturn(java.util.Optional.of(proposal));
		AgentPresence presence = new AgentPresence(Duration.ofSeconds(15));
		presence.replace("worker-local", List.of(new AgentPresence.Reported(235, 2L)));
		ScheduleLog journal = new ScheduleLog(Path.of("target", "manager-live-" + System.nanoTime()), ScheduleLog.MAX_BYTES);
		ManagerAnalysis analysis = new ManagerAnalysis(repository, sessions, new MockRuntime(), presence, journal);

		analysis.analyze("tok", project, 235, List.of("ai/managed"));

		verify(sessions, never()).say(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString());
		assertThat(journal.read("schedule.jsonl", 10).records()).filteredOn(row -> "stop".equals(row.get("action"))).isNotEmpty();
	}

	@Test
	void aHumanTodoKeepsTheManagerAsleep() {
		AgentRepository repository = mock(AgentRepository.class);
		AgentSessionService sessions = mock(AgentSessionService.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		setId(project, 3L);
		ScheduleLog journal = new ScheduleLog(Path.of("target", "manager-hitl-" + System.nanoTime()), ScheduleLog.MAX_BYTES);
		ManagerAnalysis analysis = new ManagerAnalysis(repository, sessions, new MockRuntime(), new AgentPresence(Duration.ofSeconds(15)), journal);

		analysis.analyze("tok", project, 232, List.of("ai/managed", "ai/hitl"));

		verify(sessions, never()).say(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString());
		assertThat(journal.read("schedule.jsonl", 10).records()).filteredOn(row -> "stop".equals(row.get("action")))
				.singleElement()
				.satisfies(row -> assertThat(row.get("note").toString()).contains("已经派给人"));
	}

	@Test
	void aMockKeepsTheStatusWhileWritingTheIssue() {
		String original = IssueStatusText.withStatus("### 背景\n\n有\n", "审阅中", "实现");
		MockAgent.Turn turn = MockAgent.run(new ManagerRound.Face("proposal", "proposal", "审字段。", List.of()), original,
				List.of("type/feature"), "审阅中", List.of(new ManagerRound.Note(11, "打回：背景不清楚")));
		assertThat(IssueStatusText.statusOf(turn.description())).isEqualTo("审阅中");
		assertThat(turn.text()).contains("@审阅中");
	}

	private static void setId(ProjectEntity project, long id) {
		set(project, id);
	}

	private static void setAgentId(AgentEntity agent, long id) {
		set(agent, id);
	}

	private static void set(Object target, long id) {
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
