package dev.helix.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerSessionsTest {

	@TempDir
	Path temp;

	@Test
	void gitlabEnvKeepsOnlyGitlabVariables() {
		Map<String, String> env = IssueWorkspace.gitlab(Map.of(
				"GITLAB_HOST", "https://hackers.oalite.com",
				"GITLAB_TOKEN", "secret",
				"AGENTKIT_API_KEY", "llm",
				"PATH", "/usr/bin"));

		assertThat(env).containsEntry("GITLAB_HOST", "https://hackers.oalite.com").containsEntry("GITLAB_TOKEN", "secret");
		assertThat(env).doesNotContainKey("AGENTKIT_API_KEY").doesNotContainKey("PATH");
	}

	@Test
	void eachIssueGetsItsOwnWorkspace() throws Exception {
		Path repo = temp.resolve("repo");
		Files.createDirectories(repo);
		git(repo, "init");
		git(repo, "config", "user.email", "worker@helix.test");
		git(repo, "config", "user.name", "worker");
		Files.writeString(repo.resolve("README.md"), "hello");
		git(repo, "add", "README.md");
		git(repo, "commit", "-m", "init");
		Map<String, String> env = Map.of("HELIX_REPO", repo.toString());
		Path root = temp.resolve("workers");

		Path first = IssueWorkspace.open(root, 21, env);
		Path second = IssueWorkspace.open(root, 22, env);

		assertThat(first).isEqualTo(root.resolve("issue-21"));
		assertThat(second).isEqualTo(root.resolve("issue-22"));
		assertThat(Files.readString(first.resolve("README.md"))).isEqualTo("hello");
		assertThat(IssueWorkspace.open(root, 21, env)).isEqualTo(first);
		assertThatThrownBy(() -> IssueWorkspace.open(root, 23, Map.of()))
				.isInstanceOf(WorkspaceException.class)
				.hasMessageContaining("代码仓库");
	}

	@Test
	void sparseIssueWorkspaceKeepsTheSourcePatterns() throws Exception {
		Path repo = temp.resolve("repo");
		Files.createDirectories(repo.resolve("keep"));
		Files.createDirectories(repo.resolve("skip"));
		git(repo, "init");
		git(repo, "config", "user.email", "worker@helix.test");
		git(repo, "config", "user.name", "worker");
		Files.writeString(repo.resolve("keep/note.txt"), "keep");
		Files.writeString(repo.resolve("skip/note.txt"), "skip");
		git(repo, "add", ".");
		git(repo, "commit", "-m", "init");
		git(repo, "sparse-checkout", "set", "--cone", "keep");
		Path root = temp.resolve("workers");

		Path space = IssueWorkspace.open(root, 8, Map.of("HELIX_REPO", repo.toString()));

		assertThat(space.resolve("keep/note.txt")).exists();
		assertThat(space.resolve("skip/note.txt")).doesNotExist();
	}

	@Test
	void sessionStartsTheIssueTaskWithGitlabEnv() {
		Path repo = temp.resolve("repo");
		AtomicReference<String> message = new AtomicReference<>();
		AtomicReference<Map<String, String>> gitlab = new AtomicReference<>();
		try {
			Files.createDirectories(repo);
			git(repo, "init");
			git(repo, "config", "user.email", "worker@helix.test");
			git(repo, "config", "user.name", "worker");
			Files.writeString(repo.resolve("README.md"), "hello");
			git(repo, "add", "README.md");
			git(repo, "commit", "-m", "init");
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
		WorkerSessions sessions = new WorkerSessions(temp.resolve("workers"), Map.of(
				"HELIX_REPO", repo.toString(),
				"GITLAB_HOST", "https://hackers.oalite.com",
				"GITLAB_PROJECT", "qingflow-develop/qingflow-workspace",
				"AGENTKIT_API_KEY", "llm"), (thread, text, workspace, env, sink) -> {
					message.set(text);
					gitlab.set(env);
					assertThat(workspace).isEqualTo(temp.resolve("workers/issue-21"));
				});

		WorkerSessions.Result result = sessions.accept("{\"issueNumber\":21}");

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body()).isEqualTo("{\"id\":\"issue-21\"}");
		assertThat(message.get()).contains("issue #21").contains("GITLAB_");
		assertThat(gitlab.get()).containsEntry("GITLAB_HOST", "https://hackers.oalite.com")
				.doesNotContainKey("AGENTKIT_API_KEY");
	}

	@Test
	void missingIssueNumberDoesNotStart() {
		WorkerSessions sessions = new WorkerSessions(temp, Map.of(), (thread, message, workspace, env, sink) -> {
			throw new AssertionError("不应该启动");
		});

		WorkerSessions.Result result = sessions.accept("{}");

		assertThat(result.status()).isEqualTo(400);
	}

	private static void git(Path directory, String... args) throws Exception {
		Process process = new ProcessBuilder(prepend(args)).directory(directory.toFile()).start();
		assertThat(process.waitFor()).isEqualTo(0);
	}

	private static String[] prepend(String... args) {
		String[] command = new String[args.length + 1];
		command[0] = "git";
		System.arraycopy(args, 0, command, 1, args.length);
		return command;
	}

}
