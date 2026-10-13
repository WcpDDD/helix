package dev.helix.work;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.helix.board.ProjectEntity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectWorkspaceTest {

	@TempDir
	Path temp;

	@Test
	void authorizesGitWithOauth2Basic() {
		String header = ProjectWorkspaces.authorizationHeader("secret-token");
		assertThat(header).startsWith("Authorization: Basic ");
		String decoded = new String(java.util.Base64.getDecoder().decode(header.substring("Authorization: Basic ".length())));
		assertThat(decoded).isEqualTo("oauth2:secret-token");
		assertThat(ProjectWorkspaces.CREDENTIAL_HELPER).doesNotContain("secret-token");
	}

	@Test
	void refusesADirectoryOutsideTheRoot() {
		ProjectWorkspaces workspaces = new ProjectWorkspaces(temp, null, null);
		ProjectEntity project = new ProjectEntity("越界", "example.test", "..", "repo");

		assertThatThrownBy(() -> workspaces.directory(project)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
	}

	@Test
	void readsTheMainBranchAndPushesALaterChange() throws Exception {
		Path origin = temp.resolve("origin.git");
		git(temp, "init", "--bare", "-b", "main", origin.toString());
		Path seed = temp.resolve("seed");
		git(temp, "init", "-b", "main", seed.toString());
		Files.createDirectories(seed.resolve("skills/demo"));
		Files.writeString(seed.resolve("skills/demo/SKILL.md"), "# demo");
		Files.createDirectories(seed.resolve("openspec/changes/helix-spec-reader"));
		Files.writeString(seed.resolve("openspec/changes/helix-spec-reader/proposal.md"), "# 提案");
		Files.writeString(seed.resolve("openspec/changes/helix-spec-reader/tasks.md"), "# 任务");
		commitAll(seed, "初始");
		git(seed, "remote", "add", "origin", origin.toString());
		git(seed, "push", "origin", "main");

		ProjectEntity project = new ProjectEntity("样本", "example.test", "probe", "sample");
		ProjectWorkspaces workspaces = new ProjectWorkspaces(temp.resolve("work"), null, null);
		git(temp, "clone", origin.toString(), workspaces.directory(project).toString());

		WorkSnapshot opened = workspaces.status(project, null);
		assertThat(opened.branch()).isEqualTo("main");
		assertThat(opened.push()).isEqualTo("ready");
		assertThat(opened.ahead()).isZero();
		assertThat(opened.behind()).isZero();
		assertThat(opened.dirty()).isFalse();
		assertThat(opened.commits()).isNotEmpty();
		assertThat(workspaces.markdownUnder(workspaces.directory(project), "skills"))
				.containsExactly("skills/demo/SKILL.md");

		Files.writeString(workspaces.directory(project).resolve("notes.txt"), "后续");
		WorkSnapshot edited = workspaces.status(project, null);
		assertThat(edited.push()).isEqualTo("ready");
		assertThat(edited.dirty()).isTrue();
		assertThat(edited.ahead()).isZero();
		assertThat(git(temp, "--git-dir", origin.toString(), "log", "-1", "--format=%s").strip()).isEqualTo("初始");

		workspaces.pushNow(project, null);

		WorkSnapshot pushed = workspaces.status(project, null);
		assertThat(pushed.push()).isEqualTo("ready");
		assertThat(pushed.ahead()).isZero();
		assertThat(pushed.dirty()).isFalse();
		assertThat(pushed.commits().get(0).subject()).isEqualTo("同步工作目录");
		String remote = git(temp, "clone", origin.toString(), temp.resolve("again").toString());
		assertThat(remote).isNotNull();
		assertThat(Files.readString(temp.resolve("again/notes.txt"))).isEqualTo("后续");
	}

	@Test
	void clonesOnlyTheLatestTenCommitsAndTheDirectoriesWeRead() throws Exception {
		Path origin = temp.resolve("origin.git");
		git(temp, "init", "--bare", "-b", "main", origin.toString());
		Path seed = temp.resolve("seed");
		git(temp, "init", "-b", "main", seed.toString());
		Files.createDirectories(seed.resolve("skills/demo"));
		Files.writeString(seed.resolve("skills/demo/SKILL.md"), "# demo");
		Files.createDirectories(seed.resolve("other"));
		Files.writeString(seed.resolve("other/skip.txt"), "跳过");
		commitAll(seed, "初始");
		for (int index = 2; index <= 12; index++) {
			Files.writeString(seed.resolve("skills/demo/SKILL.md"), "# demo " + index);
			commitAll(seed, "第" + index + "次");
		}
		git(seed, "remote", "add", "origin", origin.toString());
		git(seed, "push", "origin", "main");

		ProjectEntity project = new ProjectEntity("样本", "example.test", "probe", "shallow");
		ProjectWorkspaces workspaces = new ProjectWorkspaces(temp.resolve("work"), null, null);
		Path checkout = workspaces.cloneLimited(project, "main", origin.toString());

		int count = Integer.parseInt(git(checkout, "rev-list", "--count", "HEAD").strip());
		assertThat(count).isEqualTo(10);
		assertThat(Files.readString(checkout.resolve("skills/demo/SKILL.md"))).contains("12");
		assertThat(Files.exists(checkout.resolve("other/skip.txt"))).isFalse();

		ProjectEntity authed = new ProjectEntity("样本", "example.test", "probe", "authed");
		Path withToken = new ProjectWorkspaces(temp.resolve("work"), null, null)
				.cloneLimited(authed, "main", origin.toString(), "secret-token");
		String config = Files.readString(withToken.resolve(".git/config"));
		assertThat(config).contains("HELIX_GIT_TOKEN");
		assertThat(config).doesNotContain("secret-token");
	}

	@Test
	void pullFastForwardsWhenCleanAndKeepsBothSidesWhenDiverged() throws Exception {
		Path origin = temp.resolve("origin.git");
		git(temp, "init", "--bare", "-b", "main", origin.toString());
		Path seed = temp.resolve("seed");
		git(temp, "init", "-b", "main", seed.toString());
		Files.writeString(seed.resolve("base.txt"), "base");
		commitAll(seed, "初始");
		git(seed, "remote", "add", "origin", origin.toString());
		git(seed, "push", "origin", "main");

		ProjectEntity project = new ProjectEntity("样本", "example.test", "probe", "sync");
		ProjectWorkspaces workspaces = new ProjectWorkspaces(temp.resolve("work"), null, null);
		Path checkout = workspaces.directory(project);
		git(temp, "clone", origin.toString(), checkout.toString());

		Files.writeString(seed.resolve("remote.txt"), "远端");
		commitAll(seed, "远端提交");
		git(seed, "push", "origin", "main");
		workspaces.pullNow(project, null);
		WorkSnapshot followed = workspaces.status(project, null);
		assertThat(followed.push()).isEqualTo("ready");
		assertThat(followed.behind()).isZero();
		assertThat(followed.ahead()).isZero();
		assertThat(Files.readString(checkout.resolve("remote.txt"))).isEqualTo("远端");

		Files.writeString(checkout.resolve("local.txt"), "本地");
		commitAll(checkout, "本地提交");
		Files.writeString(seed.resolve("remote.txt"), "远端第二");
		commitAll(seed, "远端再提交");
		git(seed, "push", "origin", "main");
		workspaces.pullNow(project, null);
		WorkSnapshot diverged = workspaces.status(project, null);
		assertThat(diverged.push()).isEqualTo("ready");
		assertThat(diverged.ahead()).isEqualTo(1);
		assertThat(diverged.behind()).isEqualTo(1);
		assertThat(Files.exists(checkout.resolve("local.txt"))).isTrue();
		assertThat(Files.readString(checkout.resolve("remote.txt"))).isEqualTo("远端");
	}

	private static void commitAll(Path directory, String message) throws Exception {
		git(directory, "add", "-A");
		git(directory, "-c", "user.name=Helix", "-c", "user.email=helix@localhost", "commit", "-m", message);
	}

	private static String git(Path directory, String... args) throws Exception {
		List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		if (process.waitFor() != 0) {
			throw new IllegalStateException(output);
		}
		return output;
	}

}
