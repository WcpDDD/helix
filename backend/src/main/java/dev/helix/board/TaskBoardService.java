package dev.helix.board;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	private final ReleasePlanStore releases;
	private final IssueListCache issueLists;

	public TaskBoardService(IssueRepository issues, IssueMergeRequestRepository mergeRequests,
			IssueEnvironmentRepository environments, WorkspaceBoard boards, GitlabIssueClient gitlab,
			ReleasePlanStore releases, IssueListCache issueLists) {
		this.issues = issues;
		this.mergeRequests = mergeRequests;
		this.environments = environments;
		this.boards = boards;
		this.gitlab = gitlab;
		this.releases = releases;
		this.issueLists = issueLists;
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
					false,
					task.getStartedOn(),
					task.getExpectedEndOn(),
					task.getFinishedOn(),
					summary(issue, requestsByIssue, environmentsByIssue),
					children,
					edges,
					null,
					ownerNames(issue.getOwner().getDisplayName())));
		}
		views.sort(Comparator.comparingInt((TaskView view) -> Integer.parseInt(view.code())).reversed());
		ProjectEntity project = issueRows.get(0).getProject();
		return dated(project, views);
	}

	public List<TaskView> listCheckout(ProjectEntity project, BoardFile board, String token) {
		return dated(project, arrange(project, board, cached(project, token)));
	}

	public void pull(ProjectEntity project, String token) {
		int ticket = issueLists.begin(project.getId());
		List<GitlabIssueClient.IssueHead> heads = readIssues(project, token);
		issueLists.store(project.getId(), ticket, heads);
	}

	private List<GitlabIssueClient.IssueHead> cached(ProjectEntity project, String token) {
		for (int attempt = 0; attempt < 2; attempt++) {
			List<GitlabIssueClient.IssueHead> heads = issueLists.get(project.getId());
			if (heads != null) {
				return heads;
			}
			pull(project, token);
		}
		List<GitlabIssueClient.IssueHead> heads = issueLists.get(project.getId());
		return heads == null ? List.of() : heads;
	}

	private List<GitlabIssueClient.IssueHead> readIssues(ProjectEntity project, String token) {
		try {
			List<GitlabIssueClient.IssueHead> heads = gitlab.issues(token, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), IssueListCache.LIMIT);
			return heads == null ? List.of() : heads;
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "任务没有从主分支对应的 issue 读到。");
		}
	}

	private List<TaskView> dated(ProjectEntity project, List<TaskView> views) {
		Map<Integer, LocalDate> dates = releases.read(project);
		if (dates.isEmpty()) {
			return views;
		}
		List<TaskView> next = new ArrayList<>();
		for (TaskView view : views) {
			LocalDate date = dates.get(Integer.parseInt(view.code()));
			next.add(date == null ? view : view.release(date));
		}
		return next;
	}

	static List<TaskView> arrange(ProjectEntity project, BoardFile board, List<GitlabIssueClient.IssueHead> heads) {
		Map<Integer, GitlabIssueClient.IssueHead> byNumber = new LinkedHashMap<>();
		for (GitlabIssueClient.IssueHead head : heads) {
			byNumber.putIfAbsent(head.iid(), head);
		}
		Set<Integer> shown = new LinkedHashSet<>();
		List<TaskView> views = new ArrayList<>();
		for (int main : board.mains()) {
			if (board.isChild(main) || !byNumber.containsKey(main) || !shown.add(main)) {
				continue;
			}
			views.add(taskFromHead(project, main, byNumber.get(main), board, byNumber));
		}
		List<Integer> loose = new ArrayList<>();
		for (int number : byNumber.keySet()) {
			if (!board.isChild(number) && shown.add(number)) {
				loose.add(number);
			}
		}
		for (int number : loose) {
			views.add(taskFromHead(project, number, byNumber.get(number), BoardFile.empty(), byNumber));
		}
		views.sort(Comparator.comparingInt((TaskView view) -> Integer.parseInt(view.code())).reversed());
		return views;
	}

	private static TaskView taskFromHead(ProjectEntity project, int number, GitlabIssueClient.IssueHead head,
			BoardFile board, Map<Integer, GitlabIssueClient.IssueHead> byNumber) {
		String status = statusOf(head);
		String stage = declaredStage(head, status);
		List<TaskView.ChildIssueView> children = new ArrayList<>();
		for (int childNumber : board.childrenOf(number)) {
			GitlabIssueClient.IssueHead child = byNumber.get(childNumber);
			if (child != null) {
				children.add(childFromHead(project, childNumber, child));
			}
		}
		List<TaskView.EdgeView> edges = new ArrayList<>();
		for (BoardFile.Edge edge : board.edgesOf(number)) {
			String from = Integer.toString(edge.from());
			String to = Integer.toString(edge.to());
			edges.add(new TaskView.EdgeView(from + "-" + to, from, to, edge.needsContract(), edge.contractRef()));
		}
		List<String> owners = board.ownersOf(number);
		String creator = board.authorOf(number);
		if (creator.isBlank()) {
			creator = authorOf(head);
		}
		return new TaskView(Integer.toString(number), titleOf(number, head), owners.isEmpty() ? "" : owners.get(0),
				status, stage, hitl(head), null, null, null, summaryFromHead(project, number, head, creator), children,
				edges, null, owners);
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

	private static TaskView.ChildIssueView childFromHead(ProjectEntity project, int number,
			GitlabIssueClient.IssueHead head) {
		String status = statusOf(head);
		return new TaskView.ChildIssueView(Integer.toString(number), issueUrl(project, number), titleOf(number, head),
				status, declaredStage(head, status), ownerOf(head), "", authorOf(head), "", "", "", "", "",
				List.of(), List.of());
	}

	private static TaskView.IssueSummary summaryFromHead(ProjectEntity project, int number,
			GitlabIssueClient.IssueHead head, String creator) {
		String author = creator == null || creator.isBlank() ? authorOf(head) : creator;
		return new TaskView.IssueSummary(issueUrl(project, number), "", author, "", "", "", "", "", List.of(),
				List.of());
	}

	private static List<String> ownerNames(String name) {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		return List.of(name);
	}

	private static boolean hitl(GitlabIssueClient.IssueHead head) {
		return head != null && head.labels() != null && head.labels().contains(ProposalDraft.HITL);
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

	private static String statusOf(GitlabIssueClient.IssueHead head) {
		if (head == null) {
			return "提案中";
		}
		String declared = IssueStatusText.from(head.labels(), head.state(), head.description());
		return declared == null ? "提案中" : declared;
	}

	private static String declaredStage(GitlabIssueClient.IssueHead head, String status) {
		String description = head == null ? "" : head.description();
		return IssueStatusText.stageMatching(description, status, "提案");
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
