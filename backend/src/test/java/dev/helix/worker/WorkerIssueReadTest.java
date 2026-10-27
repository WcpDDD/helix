package dev.helix.worker;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.worker.WorkerRegistry.CallableWorker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerIssueReadTest {

	@Test
	void theWorkerReadsTheIssueInsteadOfReceivingIt() {
		WorkerRegistry registry = mock(WorkerRegistry.class);
		AgentSessionRepository sessions = mock(AgentSessionRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabSessionCredential credential = mock(GitlabSessionCredential.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(registry.live("worker-local"))
				.thenReturn(Optional.of(new CallableWorker("worker-local", "本机", URI.create("http://127.0.0.1:8091"))));
		when(sessions.findByWorkerIdAndIssueNumber("worker-local", 232))
				.thenReturn(List.of(new AgentSessionEntity(3L, 232, 8L, "worker-local")));
		when(projects.findById(3L)).thenReturn(Optional.of(project));
		when(credential.current()).thenReturn("tok");
		when(gitlab.detail("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232))
				.thenReturn(new GitlabIssueClient.IssueDetail(
						"目标：核对托管流程 MR https://hackers.oalite.com/root/pc-fe/-/merge_requests/23392",
						List.of("ai/managed", "status/proposal")));
		when(gitlab.comments("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232))
				.thenReturn(List.of(new GitlabComment(11, "人", "2026-10-21T00:00:00Z", "打回：背景不清楚")));
		when(gitlab.mergeRequest("tok", "hackers.oalite.com", "root", "pc-fe", 23392))
				.thenReturn(new GitlabIssueClient.MrDetail(23392, "精简建区字段", "opened",
						"feat/oauth2-workspace-simplify-fields", "5a796df98b", "success", 6,
						List.of("a.ts", "b.ts")));
		WorkerIssueRead read = new WorkerIssueRead(registry, sessions, projects, gitlab, credential);

		String body = read.read("worker-local", 232);

		assertThat(body).contains("目标：核对托管流程").contains("\"id\":11").contains("打回：背景不清楚")
				.contains("ai/managed").contains("status/proposal")
				.contains("root/pc-fe").contains("\"state\":\"opened\"")
				.contains("feat/oauth2-workspace-simplify-fields").contains("5a796df98b")
				.contains("\"changes\":6").contains("a.ts");
	}

	@Test
	void unmanageRemovesOnlyTheManagedLabel() {
		WorkerRegistry registry = mock(WorkerRegistry.class);
		AgentSessionRepository sessions = mock(AgentSessionRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabSessionCredential credential = mock(GitlabSessionCredential.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(registry.live("worker-local"))
				.thenReturn(Optional.of(new CallableWorker("worker-local", "本机", URI.create("http://127.0.0.1:8091"))));
		when(sessions.findByWorkerIdAndIssueNumber("worker-local", 232))
				.thenReturn(List.of(new AgentSessionEntity(3L, 232, 8L, "worker-local")));
		when(projects.findById(3L)).thenReturn(Optional.of(project));
		when(credential.current()).thenReturn("tok");
		WorkerIssueRead read = new WorkerIssueRead(registry, sessions, projects, gitlab, credential);

		read.unmanage("worker-local", 232);

		verify(gitlab).removeLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232, "ai/managed");
	}

	@Test
	void hitlAddsOnlyTheHumanLabel() {
		WorkerRegistry registry = mock(WorkerRegistry.class);
		AgentSessionRepository sessions = mock(AgentSessionRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabSessionCredential credential = mock(GitlabSessionCredential.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(registry.live("worker-local"))
				.thenReturn(Optional.of(new CallableWorker("worker-local", "本机", URI.create("http://127.0.0.1:8091"))));
		when(sessions.findByWorkerIdAndIssueNumber("worker-local", 232))
				.thenReturn(List.of(new AgentSessionEntity(3L, 232, 8L, "worker-local")));
		when(projects.findById(3L)).thenReturn(Optional.of(project));
		when(credential.current()).thenReturn("tok");
		WorkerIssueRead read = new WorkerIssueRead(registry, sessions, projects, gitlab, credential);

		read.hitl("worker-local", 232);

		verify(gitlab).addLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232, "ai/hitl");
	}

	@Test
	void aCommentIsPostedOntoTheIssue() {
		WorkerRegistry registry = mock(WorkerRegistry.class);
		AgentSessionRepository sessions = mock(AgentSessionRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabSessionCredential credential = mock(GitlabSessionCredential.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(registry.live("worker-local"))
				.thenReturn(Optional.of(new CallableWorker("worker-local", "本机", URI.create("http://127.0.0.1:8091"))));
		when(sessions.findByWorkerIdAndIssueNumber("worker-local", 232))
				.thenReturn(List.of(new AgentSessionEntity(3L, 232, 8L, "worker-local")));
		when(projects.findById(3L)).thenReturn(Optional.of(project));
		when(credential.current()).thenReturn("tok");
		when(gitlab.comment("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232, "工作记录：写完了"))
				.thenReturn(new GitlabComment(12, "人", "2026-10-27T00:00:00Z", "工作记录：写完了"));
		WorkerIssueRead read = new WorkerIssueRead(registry, sessions, projects, gitlab, credential);

		String body = read.comment("worker-local", 232, "工作记录：写完了");

		assertThat(body).contains("\"id\":12").contains("工作记录：写完了");
	}

}
