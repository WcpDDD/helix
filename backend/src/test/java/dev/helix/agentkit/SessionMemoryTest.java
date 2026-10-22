package dev.helix.agentkit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.agentkit.api.execution.AgentRunningContext;
import io.agentkit.api.Principal;
import io.agentkit.api.react.Checkpoint;
import io.agentkit.api.session.SessionState;
import io.agentkit.api.session.SessionStatus;
import io.agentkit.api.session.SessionStorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMemoryTest {

	@TempDir
	Path temp;

	@Test
	void threadMemorySurvivesANewRuntime() {
		String threadId = "11111111-1111-1111-1111-111111111111";
		AgentRunningContext context = new AgentRunningContext(
				new Principal("worker-local", Principal.Type.USER, Map.of()), "helix", "request");
		Checkpoint checkpoint = new Checkpoint("model", "系统提示", List.of(), List.of(), Map.of(), List.of());
		SessionState state = new SessionState(checkpoint, List.of());

		SessionMemory.open(temp).store(context, threadId, state, SessionStatus.IDLE);

		SessionStorage reopened = SessionMemory.open(temp);
		assertThat(reopened.restore(context, threadId)).isPresent();
		assertThat(reopened.restore(context, threadId).orElseThrow().checkpoint().systemPrompt()).isEqualTo("系统提示");
		assertThat(reopened.restore(context, "22222222-2222-2222-2222-222222222222")).isEmpty();
	}

}
