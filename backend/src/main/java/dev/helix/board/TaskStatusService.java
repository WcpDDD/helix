package dev.helix.board;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
public class TaskStatusService {

	private final IssueRepository issues;

	private final ProjectRepository projects;

	private final GitlabIssueClient gitlab;

	private final WorkspaceBoard boards;

	private final ReleasePlanStore releases;

	public TaskStatusService(IssueRepository issues, ProjectRepository projects, GitlabIssueClient gitlab,
			WorkspaceBoard boards, ReleasePlanStore releases) {
		this.issues = issues;
		this.projects = projects;
		this.gitlab = gitlab;
		this.boards = boards;
		this.releases = releases;
	}

	@Transactional
	public void align(String accessToken) {
		try {
			Map<ProjectEntity, List<IssueEntity>> mains = mainsByProject();
			for (Map.Entry<ProjectEntity, List<IssueEntity>> entry : mains.entrySet()) {
				ProjectEntity project = entry.getKey();
				List<IssueEntity> group = entry.getValue();
				List<Integer> numbers = group.stream().map(IssueEntity::getGitIssueNumber).toList();
				Map<Integer, GitlabIssueClient.IssueHead> remote = gitlab.heads(accessToken, project.getGitHost(),
						project.getGitOwner(), project.getGitRepo(), numbers);
				for (IssueEntity main : group) {
					GitlabIssueClient.IssueHead head = remote.get(main.getGitIssueNumber());
					if (head == null) {
						continue;
					}
					adoptIfDeclared(main, head);
				}
			}
		}
		catch (GitlabIssueException exception) {
			// 读不到主 issue 时保留库里的副本，下次再对齐。
		}
	}

	@Transactional
	public Transition transition(String accessToken, long projectId, int number, String requested, String releaseOn) {
		if (!IssueStatusText.STATUSES.contains(requested)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		LocalDate release = "待上线池".equals(requested) ? releaseDate(releaseOn) : null;
		if (boards.current(projectId).isChild(number)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		ProjectEntity project = projects.findById(projectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (release != null) {
			releases.save(project, number, release);
		}
		GitlabIssueClient.IssueDetail detail = load(accessToken, project, number);
		String current = detail.description() == null ? "" : detail.description();
		String declared = IssueStatusText.stageOf(current);
		String stage = IssueStatusText.stageFor(requested, declared == null ? "" : declared);
		String next = IssueStatusText.withStatus(current, requested, stage);
		String label = IssueStatusText.labelFor(requested);
		List<String> remove = IssueStatusText.statusLabelsToRemove(detail.labels(), label);
		boolean close = "已关闭".equals(requested) || "已驳回".equals(requested) || "已上线".equals(requested);
		try {
			gitlab.writeStatus(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), number,
					next, label, remove, close);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			return followIssue(accessToken, project, number);
		}
		issues.findInProject(projectId, number).ifPresent(main -> adopt(main, requested, stage));
		return new Transition(requested, stage, true, release == null ? null : release.toString());
	}

	public String schedule(long projectId, int number, String releaseOn) {
		LocalDate release = releaseDate(releaseOn);
		if (boards.current(projectId).isChild(number)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		ProjectEntity project = projects.findById(projectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		releases.save(project, number, release);
		return release.toString();
	}

	private Transition followIssue(String accessToken, ProjectEntity project, int number) {
		GitlabIssueClient.IssueDetail detail = load(accessToken, project, number);
		String description = detail.description() == null ? "" : detail.description();
		String issueStatus = IssueStatusText.from(detail.labels(), null, description);
		if (issueStatus == null) {
			return new Transition("提案中", "提案", false, null);
		}
		String stage = IssueStatusText.stageMatching(description, issueStatus, "");
		return new Transition(issueStatus, stage, false, null);
	}

	private static LocalDate releaseDate(String raw) {
		if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "进入待上线池需要发布日期。");
		}
		try {
			return LocalDate.parse(raw);
		}
		catch (DateTimeParseException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发布日期不对。");
		}
	}

	private void adoptIfDeclared(IssueEntity main, GitlabIssueClient.IssueHead head) {
		String description = head.description() == null ? "" : head.description();
		String issueStatus = IssueStatusText.from(head.labels(), head.state(), description);
		if (issueStatus == null) {
			return;
		}
		String stage = IssueStatusText.stageMatching(description, issueStatus, main.getTask().getRollupStage());
		if (issueStatus.equals(main.getStatus()) && stage.equals(main.getStage())
				&& issueStatus.equals(main.getTask().getRollupStatus()) && stage.equals(main.getTask().getRollupStage())) {
			return;
		}
		adopt(main, issueStatus, stage);
	}

	private void adopt(IssueEntity main, String status, String stage) {
		main.adoptStatus(status, stage);
		main.getTask().adopt(status, stage);
	}

	private Map<ProjectEntity, List<IssueEntity>> mainsByProject() {
		Map<ProjectEntity, List<IssueEntity>> mains = new LinkedHashMap<>();
		for (IssueEntity issue : issues.findAll()) {
			if (boards.current(issue.getProject().getId()).isChild(issue.getGitIssueNumber())) {
				continue;
			}
			mains.computeIfAbsent(issue.getProject(), ignored -> new ArrayList<>()).add(issue);
		}
		return mains;
	}

	private GitlabIssueClient.IssueDetail load(String accessToken, ProjectEntity project, int number) {
		try {
			GitlabIssueClient.IssueDetail detail = gitlab.detail(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), number);
			if (detail == null) {
				return new GitlabIssueClient.IssueDetail("", List.of());
			}
			return detail;
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
	}

	public record Transition(String status, String stage, boolean synced, String releaseOn) {
	}

}
