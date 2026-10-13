package dev.helix.board;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.work.ProjectWorkspaces;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceSpecServiceTest {

	@TempDir
	Path temp;

	@Test
	void readsMarkdownFromTheMainCheckout() throws Exception {
		IssueRepository issues = mock(IssueRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		ProjectEntity project = new ProjectEntity("qingflow-workspace", "hackers.oalite.com", "qingflow-develop",
				"qingflow-workspace");
		TaskEntity task = new TaskEntity(project, "提案中", "提案", LocalDate.of(2026, 10, 8), null, null);
		MemberEntity member = new MemberEntity(project, "chenxinpei", "chenxinpei");
		IssueEntity issue = new IssueEntity(project, task, true, 321, "规格读取试跑", "技术变更", "提案", "提案中", member,
				member, "范围", "半径", "结论", "", null, null, Instant.parse("2026-10-08T00:00:00Z"));
		when(issues.findInProject(1L, 321)).thenReturn(Optional.of(issue));
		when(gitlab.description("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 321))
				.thenReturn("""
						<!-- helix -->
						状态：提案中
						阶段：提案
						分支：main
						规格：openspec/changes/helix-spec-reader
						<!-- /helix -->
						""");
		ProjectWorkspaces workspaces = new ProjectWorkspaces(temp, null, null);
		Path checkout = workspaces.directory(project);
		Files.createDirectories(checkout.resolve("openspec/changes/helix-spec-reader"));
		Files.writeString(checkout.resolve("openspec/changes/helix-spec-reader/proposal.md"), "# 提案");
		Files.writeString(checkout.resolve("openspec/changes/helix-spec-reader/tasks.md"), "# 任务");
		git(checkout, "init", "-b", "main");
		git(checkout, "add", "-A");
		git(checkout, "-c", "user.name=Helix", "-c", "user.email=helix@localhost", "commit", "-m", "规格");

		SpecCheckout checkoutView = new WorkspaceSpecService(issues, gitlab, workspaces).checkout("tok", 1L, 321);

		assertThat(checkoutView.branch()).isEqualTo("main");
		assertThat(checkoutView.commit()).hasSize(7);
		assertThat(checkoutView.files()).extracting(SpecCheckout.SpecFile::path).containsExactly(
				"openspec/changes/helix-spec-reader/proposal.md",
				"openspec/changes/helix-spec-reader/tasks.md");
		assertThat(checkoutView.files().get(0).markdown()).isEqualTo("# 提案");
	}

	private static void git(Path directory, String... args) throws Exception {
		java.util.List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.addAll(java.util.List.of(args));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		if (process.waitFor() != 0) {
			throw new IllegalStateException(output);
		}
	}

}
