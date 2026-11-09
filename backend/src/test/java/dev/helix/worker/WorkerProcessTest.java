package dev.helix.worker;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerProcessTest {

	@Test
	void reloadsTheProcessWithTheToolchainOnThePath() {
		List<String> command = WorkerProcess.toolchainCommand(Path.of("/opt/helix/env.sh"));

		assertThat(command).startsWith("bash", "-c");
		assertThat(command).contains("/opt/helix/env.sh");
		assertThat(String.join(" ", command)).contains("HELIX_TOOLCHAIN_LOADED=1");
		assertThat(command).contains(WorkerProcess.class.getName());
	}

	@Test
	void reloadsThroughTheBootLauncherWhenThatStartedTheJvm() {
		String previous = System.getProperty("sun.java.command");
		System.setProperty("sun.java.command", "org.springframework.boot.loader.launch.PropertiesLauncher");
		try {
			List<String> command = WorkerProcess.toolchainCommand(Path.of("/opt/helix/env.sh"));
			assertThat(command).contains("org.springframework.boot.loader.launch.PropertiesLauncher");
		}
		finally {
			if (previous == null) {
				System.clearProperty("sun.java.command");
			}
			else {
				System.setProperty("sun.java.command", previous);
			}
		}
	}

	@Test
	void advertisesTheConfiguredAddressAndKeepsLoopbackForALocalWorker() {
		assertThat(WorkerProcess.advertised("", 8091)).isEqualTo("http://127.0.0.1:8091");
		assertThat(WorkerProcess.advertised("http://helix-worker.helix.svc.cluster.local:8091/", 8091))
				.isEqualTo("http://helix-worker.helix.svc.cluster.local:8091");
	}

	@Test
	void theHeartbeatNamesTheAgentsStillRunning() {
		String body = WorkerProcess.heartbeatJson("worker-local", "本机", "http://127.0.0.1:8091",
				List.of(new WorkerSessions.Run(235, "developer")));

		assertThat(body).contains("\"issue\":235");
		assertThat(body).contains("\"agent\":\"developer\"");
	}

}
