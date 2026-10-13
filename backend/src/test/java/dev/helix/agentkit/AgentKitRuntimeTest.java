package dev.helix.agentkit;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentKitRuntimeTest {

	@Test
	void blankRequestStaysAtTheConfiguredRoot() {
		Path root = Path.of("/tmp/helix-agents");

		assertThat(AgentKitRuntime.resolveWorkspace(root, " ")).isEqualTo(root.toAbsolutePath().normalize());
	}

	@Test
	void relativeWorkspaceStaysInsideTheRoot() {
		Path root = Path.of("/tmp/helix-agents");

		assertThat(AgentKitRuntime.resolveWorkspace(root, "qingflow")).isEqualTo(root.resolve("qingflow").toAbsolutePath().normalize());
	}

	@Test
	void workspaceCannotLeaveTheRoot() {
		Path root = Path.of("/tmp/helix-agents");

		assertThatThrownBy(() -> AgentKitRuntime.resolveWorkspace(root, "../secrets"))
				.isInstanceOf(ResponseStatusException.class);
	}

}
