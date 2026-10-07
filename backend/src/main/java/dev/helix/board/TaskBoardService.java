package dev.helix.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskBoardService {

	private final IssueRepository issues;
	private final IssueMergeRequestRepository mergeRequests;
	private final IssueEnvironmentRepository environments;
	private final WorkspaceBoard boards;

	public TaskBoardService(IssueRepository issues, IssueMergeRequestRepository mergeRequests,
			IssueEnvironmentRepository environments, WorkspaceBoard boards) {
		this.issues = issues;
		this.mergeRequests = mergeRequests;
		this.environments = environments;
		this.boards = boards;
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
		ProjectEntity project = issue.getProject();
		String host = project.getGitHost() == null ? "" : project.getGitHost().trim();
		while (host.endsWith("/")) {
			host = host.substring(0, host.length() - 1);
		}
		if (!host.startsWith("http://") && !host.startsWith("https://")) {
			host = "https://" + host;
		}
		return host + "/" + project.getGitOwner() + "/" + project.getGitRepo() + "/-/issues/" + issue.getGitIssueNumber();
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
