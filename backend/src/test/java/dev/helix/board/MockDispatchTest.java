package dev.helix.board;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.OpenedIssue;
import dev.helix.gitlab.IssueStatusText;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MockDispatchTest {

	@Test
	void aDispatchedRoleWritesTheIssueAndSkipsAgentKit() {
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabSessionCredential credential = new GitlabSessionCredential();
		credential.remember("tok");
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		AgentEntity proposal = new AgentEntity(project, "proposal", "审字段。材料齐了才写回。");
		proposal.retag("proposal");
		when(gitlab.description("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232))
				.thenReturn(IssueStatusText.withStatus("只有一句。", "提案中", "提案"));
		when(gitlab.comments("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232))
				.thenReturn(List.of(new GitlabComment(11, "人", "2026-10-21T00:00:00Z", "打回：背景不清楚")));
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace"))
				.thenReturn(List.of(new OpenedIssue(232, List.of("ai/managed"))));

		MockDispatch dispatch = new MockDispatch(gitlab, credential, new MockRuntime());
		dispatch.chance(() -> false);
		String body = dispatch.answer(proposal, 232);

		assertThat(body).contains("类型不对。");
		verify(gitlab).writeDescription(eq("tok"), eq("hackers.oalite.com"), eq("qingflow-develop"), eq("qingflow-workspace"),
				eq(232), anyString(), eq(false));
		verify(gitlab, org.mockito.Mockito.never()).writeDescription(anyString(), anyString(), anyString(), anyString(), anyInt(),
				anyString(), eq(true));
		assertThat(body).contains("状态仍是 提案中");
	}

	@Test
	void aRunningMockStaysBusyUntilTheLeaseEndsAndMayAdvanceStatus() {
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabSessionCredential credential = new GitlabSessionCredential();
		credential.remember("tok");
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		AgentEntity developer = new AgentEntity(project, "developer", "你是 developer。");
		developer.retag("developer");
		String description = IssueStatusText.withStatus("只有一句。", "实现中", "实现");
		when(gitlab.description("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232)).thenReturn(description);
		when(gitlab.comments("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232)).thenReturn(List.of());
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace"))
				.thenReturn(List.of(new OpenedIssue(232, List.of("ai/managed", "type/feature"))));
		MovingClock clock = new MovingClock(Instant.parse("2026-10-21T10:00:00Z"));
		MockDispatch dispatch = new MockDispatch(gitlab, credential, new MockRuntime(clock));
		dispatch.chance(() -> true);

		String first = dispatch.answer(developer, 232);
		String second = dispatch.answer(developer, 232);
		clock.at(Instant.parse("2026-10-21T10:03:00Z"));
		dispatch.chance(() -> false);
		String third = dispatch.answer(developer, 232);

		assertThat(first).contains("状态改为 审阅中");
		assertThat(second).contains("还在运行");
		assertThat(third).contains("状态仍是 实现中");
		verify(gitlab, times(1)).writeDescription(eq("tok"), eq("hackers.oalite.com"), eq("qingflow-develop"), eq("qingflow-workspace"),
				eq(232), contains("状态：审阅中"), eq(false));
		verify(gitlab, times(2)).writeDescription(eq("tok"), eq("hackers.oalite.com"), eq("qingflow-develop"), eq("qingflow-workspace"),
				eq(232), anyString(), eq(false));
	}

	private static final class MovingClock extends Clock {

		private Instant now;

		private MovingClock(Instant now) {
			this.now = now;
		}

		private void at(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
