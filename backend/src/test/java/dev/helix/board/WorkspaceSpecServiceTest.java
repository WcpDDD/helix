package dev.helix.board;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabRepositoryClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceSpecServiceTest {

	@Test
	void readsMarkdownUnderTheSpecDirectory() {
		IssueRepository issues = mock(IssueRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		GitlabRepositoryClient repository = mock(GitlabRepositoryClient.class);
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
		when(repository.commitSha("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", "main"))
				.thenReturn("abc1234ffff");
		when(repository.blobs("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace",
				"openspec/changes/helix-spec-reader", "main")).thenReturn(java.util.List.of(
						"openspec/changes/helix-spec-reader/tasks.md",
						"openspec/changes/helix-spec-reader/proposal.md"));
		when(repository.fileAt("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace",
				"openspec/changes/helix-spec-reader/proposal.md", "main")).thenReturn("# 提案");
		when(repository.fileAt("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace",
				"openspec/changes/helix-spec-reader/tasks.md", "main")).thenReturn("# 任务");

		SpecCheckout checkout = new WorkspaceSpecService(issues, gitlab, repository).checkout("tok", 1L, 321);

		assertThat(checkout.branch()).isEqualTo("main");
		assertThat(checkout.commit()).isEqualTo("abc1234");
		assertThat(checkout.files()).extracting(SpecCheckout.SpecFile::path).containsExactly(
				"openspec/changes/helix-spec-reader/proposal.md",
				"openspec/changes/helix-spec-reader/tasks.md");
		assertThat(checkout.files().get(0).markdown()).isEqualTo("# 提案");
	}

}
