package dev.helix.board;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import dev.helix.worker.AgentSessionEntity;
import dev.helix.worker.AgentSessionRepository;
import dev.helix.worker.WorkerConversationClient;
import dev.helix.worker.WorkerRegistry;
import dev.helix.worker.WorkerRegistry.CallableWorker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceSpecServiceTest {

	@Test
	void readsTheSpecFromTheWorker() {
		AgentSessionRepository sessions = mock(AgentSessionRepository.class);
		WorkerRegistry registry = mock(WorkerRegistry.class);
		WorkerConversationClient workers = mock(WorkerConversationClient.class);
		when(sessions.findByProjectIdAndIssueNumberOrderByAgentIdAsc(1L, 321))
				.thenReturn(List.of(new AgentSessionEntity(1L, 321, 2L, "worker-local")));
		when(registry.live("worker-local"))
				.thenReturn(Optional.of(new CallableWorker("worker-local", "本机", URI.create("http://127.0.0.1:8091"))));
		when(workers.get(URI.create("http://127.0.0.1:8091"), "/sessions/321/spec")).thenReturn(
				new WorkerConversationClient.Exchange(200, """
						{"branch":"docs/smart-fill","commit":"abc1234","files":[
						  {"path":"openspec/changes/docs-smart-fill/proposal.md","markdown":"# 提案"}
						]}
						"""));

		SpecCheckout checkout = new WorkspaceSpecService(sessions, registry, workers).checkout(1L, 321);

		assertThat(checkout.branch()).isEqualTo("docs/smart-fill");
		assertThat(checkout.commit()).isEqualTo("abc1234");
		assertThat(checkout.files()).extracting(SpecCheckout.SpecFile::path)
				.containsExactly("openspec/changes/docs-smart-fill/proposal.md");
		assertThat(checkout.files().get(0).markdown()).isEqualTo("# 提案");
	}

	@Test
	void refusesWhenNoWorkerIsAlive() {
		AgentSessionRepository sessions = mock(AgentSessionRepository.class);
		when(sessions.findByProjectIdAndIssueNumberOrderByAgentIdAsc(1L, 321)).thenReturn(List.of());

		assertThatThrownBy(() -> new WorkspaceSpecService(sessions, mock(WorkerRegistry.class), mock(WorkerConversationClient.class))
				.checkout(1L, 321))
				.hasMessageContaining("还没有存活的 worker");
	}

}
