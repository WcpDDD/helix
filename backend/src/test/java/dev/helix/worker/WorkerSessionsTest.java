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
		assertThat(first.resolve("agents.md")).doesNotExist();
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
		AtomicReference<AgentProfile> turn = new AtomicReference<>();
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
				"AGENTKIT_API_KEY", "llm"), (thread, text, workspace, env, sink, profile) -> {
					message.set(text);
					gitlab.set(env);
					turn.set(profile);
					assertThat(workspace).isEqualTo(temp.resolve("workers/issue-21"));
				});

		WorkerSessions.Result created = sessions.accept("{\"issueNumber\":21}");
		WorkerSessions.Result page = sessions.list(21, null);
		WorkerSessions.Result sent = sessions.send(21, "{\"text\":\"看一下\"}");

		assertThat(created.status()).isEqualTo(200);
		assertThat(created.body()).isEqualTo("{\"id\":\"issue-21\"}");
		assertThat(page.body()).contains("\"messages\":[]");
		assertThat(sent.status()).isEqualTo(200);
		assertThat(message.get()).isEqualTo("看一下");
		assertThat(gitlab.get()).containsEntry("GITLAB_HOST", "https://hackers.oalite.com")
				.doesNotContainKey("AGENTKIT_API_KEY");
		assertThat(turn.get().body()).isEmpty();
		assertThat(turn.get().skills()).containsExactly(IssueWorkspace.NO_SKILL);
	}

	@Test
	void turnEnablesOnlyTheRequestedSkill() throws Exception {
		Path repo = temp.resolve("repo");
		Files.createDirectories(repo.resolve("skills/dev-flow"));
		Files.createDirectories(repo.resolve("skills/group/writing"));
		Files.createDirectories(repo.resolve("skills/plain"));
		git(repo, "init");
		git(repo, "config", "user.email", "worker@helix.test");
		git(repo, "config", "user.name", "worker");
		Files.writeString(repo.resolve("README.md"), "hello");
		Files.writeString(repo.resolve("skills/dev-flow/SKILL.md"), """
				---
				name: dev-flow
				description: 开发
				---
				按这个流程做。
				""");
		Files.writeString(repo.resolve("skills/group/writing/SKILL.md"), """
				---
				name: "writing"
				description: 写作
				---
				写。
				""");
		Files.writeString(repo.resolve("skills/plain/note.md"), "不是技能");
		git(repo, "add", ".");
		git(repo, "commit", "-m", "init");
		AtomicReference<AgentProfile> turn = new AtomicReference<>();
		WorkerSessions sessions = new WorkerSessions(temp.resolve("workers"), Map.of("HELIX_REPO", repo.toString()),
				(thread, text, workspace, env, sink, profile) -> turn.set(profile));

		WorkerSessions.Result created = sessions.accept("""
				{"issueNumber":21,"agentBody":"你是 arch。","skills":["skills/dev-flow/SKILL.md","../secret/SKILL.md"]}
				""");
		WorkerSessions.Result sent = sessions.send(21, """
				{"text":"开始","agentBody":"你是 arch。","skills":["skills/dev-flow/SKILL.md","../secret/SKILL.md"]}
				""");

		assertThat(created.status()).isEqualTo(200);
		assertThat(sent.status()).isEqualTo(200);
		Path space = temp.resolve("workers/issue-21");
		assertThat(turn.get().body()).isEqualTo("你是 arch。");
		assertThat(turn.get().skills()).containsExactly("dev-flow");
		assertThat(Files.readSymbolicLink(space.resolve(".agents/skills/dev-flow")))
				.isEqualTo(Path.of("..", "..", "skills", "dev-flow"));
		assertThat(Files.readSymbolicLink(space.resolve(".agents/skills/writing")))
				.isEqualTo(Path.of("..", "..", "skills", "group", "writing"));
		assertThat(space.resolve(".agents/skills/plain")).doesNotExist();
		assertThat(IssueWorkspace.enabledSkills(space, java.util.List.of())).containsExactly(IssueWorkspace.NO_SKILL);
		assertThat(IssueWorkspace.open(temp.resolve("workers"), 21, Map.of("HELIX_REPO", repo.toString()))).isEqualTo(space);
		assertThat(WorkerProcess.instructions("你是 arch。", 21, "http://127.0.0.1:8080", "worker-local"))
				.contains("你是 arch。").contains("工作方式").contains("issues/21")
				.doesNotContain("你是这张 issue 的 manager").doesNotContain("调度其他 Agent")
				.doesNotContain("/issues/21/hitl").doesNotContain("/unmanage").doesNotContain("/tasks");
		assertThat(WorkerProcess.instructions("# proposal\n你写提案。", 21, "http://127.0.0.1:8080", "worker-local"))
				.contains("工作方式").doesNotContain("你是这张 issue 的 manager")
				.doesNotContain("/issues/21/hitl").doesNotContain("/tasks");
		assertThat(WorkerProcess.instructions("# manager\n你维护这张 issue。", 21, "http://127.0.0.1:8080", "worker-local"))
				.contains("目标").contains("调度其他 Agent").contains("叫醒")
				.contains("交给人确认时，打上 ai/hitl，并摘掉 ai/managed")
				.doesNotContain("agents.md 里").doesNotContain("# 工作方式");
		assertThat(WorkerGuide.markdown(21, "http://127.0.0.1:8080", "worker-local")).contains("做到测试通过").contains("一轮只派一个")
				.contains("自己拉取这张 issue").contains("摘掉 ai/managed")
				.contains("你是被定期唤起的").contains("没有人机协同的要求").contains("没有 agent 正在工作")
				.contains("流程规范").contains("references/deliverables.md").contains("当前 agent 的执行结果")
				.contains("生成冒烟用例").contains("不跑冒烟").contains("跑最终测试")
				.contains("交给人确认时，打上 ai/hitl，并摘掉 ai/managed").contains("交给人确认：打上 ai/hitl，摘掉 ai/managed")
				.contains("POST http://127.0.0.1:8080/api/workers/worker-local/issues/21/hitl")
				.contains("POST http://127.0.0.1:8080/api/workers/worker-local/issues/21/unmanage")
				.contains("/api/workers/worker-local/issues/21/agents/{agent}/tasks")
				.doesNotContain("# 工作方式");
		assertThat(WorkerGuide.worker(21, "http://127.0.0.1:8080", "worker-local")).contains("无法自决时立刻停")
				.contains("GET http://127.0.0.1:8080/api/workers/worker-local/issues/21")
				.doesNotContain("调度其他 Agent").doesNotContain("/issues/21/hitl").doesNotContain("/tasks");
		assertThat(WorkerProcess.instructions("  ", 21, "http://127.0.0.1:8080", "worker-local")).doesNotContain("这个 Agent 的定义");
	}

	@Test
	void missingIssueNumberDoesNotStart() {
		WorkerSessions sessions = new WorkerSessions(temp, Map.of(), (thread, message, workspace, env, sink, profile) -> {
			throw new AssertionError("不应该启动");
		});

		WorkerSessions.Result result = sessions.accept("{}");

		assertThat(result.status()).isEqualTo(400);
	}

	private static String gitText(Path directory, String... args) throws Exception {
		Process process = new ProcessBuilder(prepend(args)).directory(directory.toFile()).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertThat(process.waitFor()).isEqualTo(0);
		return output;
	}

	private static void git(Path directory, String... args) throws Exception {
		gitText(directory, args);
	}

	private static String[] prepend(String... args) {
		String[] command = new String[args.length + 1];
		command[0] = "git";
		System.arraycopy(args, 0, command, 1, args.length);
		return command;
	}

}
