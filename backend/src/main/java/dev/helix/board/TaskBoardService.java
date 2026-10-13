package dev.helix.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.IssueStatusText;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskBoardService {

	private final IssueRepository issues;
	private final IssueMergeRequestRepository mergeRequests;
	private final IssueEnvironmentRepository environments;
	private final WorkspaceBoard boards;
	private final GitlabIssueClient gitlab;

	public TaskBoardService(IssueRepository issues, IssueMergeRequestRepository mergeRequests,
			IssueEnvironmentRepository environments, WorkspaceBoard boards, GitlabIssueClient gitlab) {
		this.issues = issues;
		this.mergeRequests = mergeRequests;
		this.environments = environments;
		this.boards = boards;
		this.gitlab = gitlab;
	}

	@Transactional
	public List<TaskView> list(long projectId) {
		List<IssueEntity> issueRows = issues.findForProject(projectId);
		if (issueRows.isEmpty()) {
			return List.of();
		}
		BoardFile board = boards.current(projectId);
		Map<Integer, IssueEntity> byNumber = new LinkedHashMap<>();
		for (IssueEntity issue : issueRows) {
			byNumber.put(issue.getGitIssueNumber(), issue);
		}
		List<Long> issueIds = issueRows.stream().map(IssueEntity::getId).toList();
		Map<Long, List<TaskView.MergeRequestView>> requestsByIssue = groupRequests(issueIds);
		Map<Long, List<TaskView.EnvironmentView>> environmentsByIssue = groupEnvironments(issueIds);

		List<TaskView> views = new ArrayList<>();
		for (IssueEntity issue : issueRows) {
			int number = issue.getGitIssueNumber();
			if (board.isChild(number)) {
				continue;
			}
			TaskEntity task = issue.getTask();
			List<TaskView.ChildIssueView> children = new ArrayList<>();
			for (int childNumber : board.childrenOf(number)) {
				IssueEntity child = byNumber.get(childNumber);
				if (child != null) {
					children.add(child(child, requestsByIssue, environmentsByIssue));
				}
			}
			List<TaskView.EdgeView> edges = new ArrayList<>();
			for (BoardFile.Edge edge : board.edgesOf(number)) {
				String from = Integer.toString(edge.from());
				String to = Integer.toString(edge.to());
				edges.add(new TaskView.EdgeView(from + "-" + to, from, to, edge.needsContract(), edge.contractRef()));
			}
			views.add(new TaskView(
					Integer.toString(number),
					issue.getTitle(),
					issue.getOwner().getDisplayName(),
					task.getRollupStatus(),
					task.getRollupStage(),
					task.getStartedOn(),
					task.getExpectedEndOn(),
					task.getFinishedOn(),
					summary(issue, requestsByIssue, environmentsByIssue),
					children,
					edges));
		}
		views.sort(Comparator.comparingInt((TaskView view) -> Integer.parseInt(view.code())).reversed());
		return views;
	}

	public List<TaskView> listCheckout(ProjectEntity project, BoardFile board, String token) {
		List<Integer> numbers = new ArrayList<>();
		for (int main : board.mains()) {
			numbers.add(main);
			numbers.addAll(board.childrenOf(main));
		}
		Map<Integer, GitlabIssueClient.IssueHead> heads;
		try {
			heads = gitlab.heads(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), numbers);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "任务没有从主分支对应的 issue 读到。");
		}
		List<TaskView> views = new ArrayList<>();
		for (int main : board.mains()) {
			GitlabIssueClient.IssueHead head = heads.get(main);
			String description = head == null ? "" : head.description();
			String status = declaredStatus(description);
			String stage = declaredStage(description, status);
			List<TaskView.ChildIssueView> children = new ArrayList<>();
			for (int childNumber : board.childrenOf(main)) {
				children.add(childFromHead(project, childNumber, heads.get(childNumber)));
			}
			List<TaskView.EdgeView> edges = new ArrayList<>();
			for (BoardFile.Edge edge : board.edgesOf(main)) {
				String from = Integer.toString(edge.from());
				String to = Integer.toString(edge.to());
				edges.add(new TaskView.EdgeView(from + "-" + to, from, to, edge.needsContract(), edge.contractRef()));
			}
			views.add(new TaskView(Integer.toString(main), titleOf(main, head), ownerOf(head), status, stage, null, null,
					null, summaryFromHead(project, main, head), children, edges));
		}
		views.sort(Comparator.comparingInt((TaskView view) -> Integer.parseInt(view.code())).reversed());
		return views;
	}

	private TaskView.IssueSummary summary(IssueEntity issue, Map<Long, List<TaskView.MergeRequestView>> requests,
			Map<Long, List<TaskView.EnvironmentView>> environmentsByIssue) {
		return new TaskView.IssueSummary(
				issueUrl(issue),
				issue.getKind(),
				issue.getAuthor().getDisplayName(),
				issue.getAllowedScope(),
				issue.getBlastRadius(),
				issue.getReviewSummary(),
				issue.getTargetVersion(),
				issue.getConclusion(),
				requests.getOrDefault(issue.getId(), List.of()),
				environmentsByIssue.getOrDefault(issue.getId(), List.of()));
	}

	private TaskView.ChildIssueView child(IssueEntity issue, Map<Long, List<TaskView.MergeRequestView>> requests,
			Map<Long, List<TaskView.EnvironmentView>> environmentsByIssue) {
		return new TaskView.ChildIssueView(
				Integer.toString(issue.getGitIssueNumber()),
				issueUrl(issue),
				issue.getTitle(),
				issue.getStatus(),
				issue.getStage(),
				issue.getOwner().getDisplayName(),
				issue.getKind(),
				issue.getAuthor().getDisplayName(),
				issue.getAllowedScope(),
				issue.getBlastRadius(),
				issue.getReviewSummary(),
				issue.getTargetVersion(),
				issue.getConclusion(),
				requests.getOrDefault(issue.getId(), List.of()),
				environmentsByIssue.getOrDefault(issue.getId(), List.of()));
	}

	private static String issueUrl(IssueEntity issue) {
		return issueUrl(issue.getProject(), issue.getGitIssueNumber());
	}

	private TaskView.ChildIssueView childFromHead(ProjectEntity project, int number, GitlabIssueClient.IssueHead head) {
		String description = head == null ? "" : head.description();
		String status = declaredStatus(description);
		return new TaskView.ChildIssueView(Integer.toString(number), issueUrl(project, number), titleOf(number, head),
				status, declaredStage(description, status), ownerOf(head), "", authorOf(head), "", "", "", "", "",
				List.of(), List.of());
	}

	private TaskView.IssueSummary summaryFromHead(ProjectEntity project, int number, GitlabIssueClient.IssueHead head) {
		return new TaskView.IssueSummary(issueUrl(project, number), "", authorOf(head), "", "", "", "", "", List.of(),
				List.of());
	}

	private static String titleOf(int number, GitlabIssueClient.IssueHead head) {
		if (head == null || head.title().isBlank()) {
			return "issue " + number;
		}
		return head.title();
	}

	private static String ownerOf(GitlabIssueClient.IssueHead head) {
		if (head == null) {
			return "";
		}
		if (!head.assignee().isBlank()) {
			return head.assignee();
		}
		return head.author();
	}

	private static String authorOf(GitlabIssueClient.IssueHead head) {
		return head == null ? "" : head.author();
	}

	private static String declaredStatus(String description) {
		String status = IssueStatusText.statusOf(description);
		return status != null && IssueStatusText.STATUSES.contains(status) ? status : "提案中";
	}

	private static String declaredStage(String description, String status) {
		String stage = IssueStatusText.stageOf(description);
		return stage == null ? IssueStatusText.stageFor(status, "提案") : stage;
	}

	private static String issueUrl(ProjectEntity project, int number) {
		String host = project.getGitHost() == null ? "" : project.getGitHost().trim();
		while (host.endsWith("/")) {
			host = host.substring(0, host.length() - 1);
		}
		if (!host.startsWith("http://") && !host.startsWith("https://")) {
			host = "https://" + host;
		}
		return host + "/" + project.getGitOwner() + "/" + project.getGitRepo() + "/-/issues/" + number;
	}

	private Map<Long, List<TaskView.MergeRequestView>> groupRequests(List<Long> issueIds) {
		Map<Long, List<TaskView.MergeRequestView>> grouped = new LinkedHashMap<>();
		if (issueIds.isEmpty()) {
			return grouped;
		}
		for (IssueMergeRequestEntity request : mergeRequests.findByIssue_IdInOrderByIidAsc(issueIds)) {
			grouped.computeIfAbsent(request.getIssueId(), ignored -> new ArrayList<>())
					.add(new TaskView.MergeRequestView(request.getIid(), request.getTitle(), request.getState(),
							request.getWebUrl()));
		}
		return grouped;
	}

	private Map<Long, List<TaskView.EnvironmentView>> groupEnvironments(List<Long> issueIds) {
		Map<Long, List<TaskView.EnvironmentView>> grouped = new LinkedHashMap<>();
		if (issueIds.isEmpty()) {
			return grouped;
		}
		for (IssueEnvironmentEntity environment : environments.findByIssue_IdInOrderByNameAsc(issueIds)) {
			grouped.computeIfAbsent(environment.getIssueId(), ignored -> new ArrayList<>())
					.add(new TaskView.EnvironmentView(environment.getName(), environment.getUrl()));
		}
		return grouped;
	}

}
