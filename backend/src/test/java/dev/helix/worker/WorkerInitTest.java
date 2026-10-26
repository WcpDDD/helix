package dev.helix.worker;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerInitTest {

	@TempDir
	Path temp;

	@Test
	void runsTheInitScriptWhenTheVersionIsNew() throws Exception {
		Path repo = repo("1", "printf 'ran\\n' >> \"$PWD/ran\"\n");
		Path root = temp.resolve("workers");

		WorkerInit.ensure(root, repo);

		assertThat(Files.readString(root.resolve("init-version"))).isEqualTo("1\n");
		assertThat(Files.readString(repo.resolve("worker-init/ran"))).isEqualTo("ran\n");
	}

	@Test
	void skipsWhenTheStampMatches() throws Exception {
		Path repo = repo("1", "printf 'ran\\n' >> \"$PWD/ran\"\n");
		Path root = temp.resolve("workers");
		WorkerInit.ensure(root, repo);

		WorkerInit.ensure(root, repo);

		assertThat(Files.readString(repo.resolve("worker-init/ran"))).isEqualTo("ran\n");
	}

	@Test
	void runsAgainWhenTheVersionChanges() throws Exception {
		Path repo = repo("1", "printf 'ran\\n' >> \"$PWD/ran\"\n");
		Path root = temp.resolve("workers");
		WorkerInit.ensure(root, repo);
		Files.writeString(repo.resolve("worker-init/VERSION"), "2\n");

		WorkerInit.ensure(root, repo);

		assertThat(Files.readString(root.resolve("init-version"))).isEqualTo("2\n");
		assertThat(Files.readString(repo.resolve("worker-init/ran"))).isEqualTo("ran\nran\n");
	}

	@Test
	void leavesTheStampUnsetWhenTheScriptFails() throws Exception {
		Path repo = repo("1", "echo no >&2\nexit 1\n");
		Path root = temp.resolve("workers");

		assertThatThrownBy(() -> WorkerInit.ensure(root, repo))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("环境初始化没有完成");
		assertThat(root.resolve("init-version")).doesNotExist();
	}

	@Test
	void doesNothingWhenTheRepoHasNoInit() {
		WorkerInit.ensure(temp.resolve("workers"), temp.resolve("repo"));
		assertThat(temp.resolve("workers/init-version")).doesNotExist();
	}

	private Path repo(String version, String body) throws Exception {
		Path directory = temp.resolve("repo-" + version).resolve("worker-init");
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("VERSION"), version + "\n");
		Files.writeString(directory.resolve("init.sh"), body);
		return directory.getParent();
	}

}
