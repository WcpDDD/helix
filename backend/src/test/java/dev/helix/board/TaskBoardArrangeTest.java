package dev.helix.board;

import java.util.List;

import dev.helix.gitlab.GitlabIssueClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskBoardArrangeTest {

	@Test
	void issuesKeepTheirStatusAndFollowTheBoard() {
		BoardFile board = BoardFile.parse("""
				version: 1
				tasks:
				  - main: 21
				    children:
				      - 22
				""");
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		List<TaskView> tasks = TaskBoardService.arrange(project, board, List.of(
				head(21, "主任务", "<!-- helix -->\n状态：实现中\n阶段：实现\n<!-- /helix -->", "opened"),
				head(22, "子任务", "", "opened"),
				head(36, "单独的 issue", "", "closed")));

		TaskView practice = find(tasks, "21");
		assertThat(practice.status()).isEqualTo("实现中");
		assertThat(practice.stage()).isEqualTo("实现");
		assertThat(practice.issues()).hasSize(1);
		assertThat(practice.issues().get(0).id()).isEqualTo("22");
		assertThat(practice.issues().get(0).status()).isEqualTo("提案中");
		TaskView detached = find(tasks, "36");
		assertThat(detached.status()).isEqualTo("已关闭");
		assertThat(detached.issues()).isEmpty();
		assertThat(practice.hitl()).isFalse();
	}

	@Test
	void aHumanTodoIsMarkedOnTheTask() {
		ProjectEntity project = new ProjectEntity("qingflow", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		List<TaskView> tasks = TaskBoardService.arrange(project, BoardFile.empty(), List.of(
				new GitlabIssueClient.IssueHead(21, "待人", "", "ada", "", "opened", List.of("ai/managed", "ai/hitl"))));

		assertThat(find(tasks, "21").hitl()).isTrue();
	}

	private static GitlabIssueClient.IssueHead head(int iid, String title, String description, String state) {
		return new GitlabIssueClient.IssueHead(iid, title, description, "ada", "", state, List.of());
	}

	private static TaskView find(List<TaskView> tasks, String code) {
		return tasks.stream().filter(task -> code.equals(task.code())).findFirst().orElseThrow();
	}

}
