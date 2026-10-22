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
		when(gitlab.description("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232))
				.thenReturn("目标：核对托管流程");
		when(gitlab.comments("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 232))
				.thenReturn(List.of(new GitlabComment(11, "人", "2026-10-21T00:00:00Z", "打回：背景不清楚")));
		WorkerIssueRead read = new WorkerIssueRead(registry, sessions, projects, gitlab, credential);

		String body = read.read("worker-local", 232);

		assertThat(body).contains("目标：核对托管流程").contains("\"id\":11").contains("打回：背景不清楚");
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

}
