package dev.helix.board;

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

	public TaskStatusService(IssueRepository issues, ProjectRepository projects, GitlabIssueClient gitlab,
			WorkspaceBoard boards) {
		this.issues = issues;
		this.projects = projects;
		this.gitlab = gitlab;
		this.boards = boards;
	}

	@Transactional
	public void align(String accessToken) {
		try {
			Map<ProjectEntity, List<IssueEntity>> mains = mainsByProject();
			for (Map.Entry<ProjectEntity, List<IssueEntity>> entry : mains.entrySet()) {
				ProjectEntity project = entry.getKey();
				List<IssueEntity> group = entry.getValue();
				List<Integer> numbers = group.stream().map(IssueEntity::getGitIssueNumber).toList();
				Map<Integer, String> remote = gitlab.descriptions(accessToken, project.getGitHost(), project.getGitOwner(),
						project.getGitRepo(), numbers);
				for (IssueEntity main : group) {
					String description = remote.get(main.getGitIssueNumber());
					if (description == null) {
						continue;
					}
					adoptIfDeclared(main, description);
				}
			}
		}
		catch (GitlabIssueException exception) {
			// 读不到主 issue 时保留库里的副本，下次再对齐。
		}
	}

	@Transactional
	public Transition transition(String accessToken, long projectId, int number, String requested) {
		if (!IssueStatusText.STATUSES.contains(requested)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		if (boards.current(projectId).isChild(number)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		ProjectEntity project = projects.findById(projectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String current = read(accessToken, project, number);
		String declared = IssueStatusText.stageOf(current);
		String stage = IssueStatusText.stageFor(requested, declared == null ? "" : declared);
		String next = IssueStatusText.withStatus(current, requested, stage);
		boolean close = "已关闭".equals(requested) || "已驳回".equals(requested) || "已上线".equals(requested);
		try {
			gitlab.writeDescription(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), number,
					next, close);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			return followIssue(accessToken, project, number);
		}
		issues.findInProject(projectId, number).ifPresent(main -> adopt(main, requested, stage));
		return new Transition(requested, stage, true);
	}

	private Transition followIssue(String accessToken, ProjectEntity project, int number) {
		String description = read(accessToken, project, number);
		String issueStatus = IssueStatusText.statusOf(description);
		if (issueStatus == null) {
			return new Transition("提案中", "提案", false);
		}
		String declared = IssueStatusText.stageOf(description);
		String stage = declared == null ? IssueStatusText.stageFor(issueStatus, "") : declared;
		return new Transition(issueStatus, stage, false);
	}

	private void adoptIfDeclared(IssueEntity main, String description) {
		String issueStatus = IssueStatusText.statusOf(description);
		if (issueStatus == null) {
			return;
		}
		String stage = stageFromIssue(description, issueStatus, main.getTask().getRollupStage());
		if (issueStatus.equals(main.getStatus()) && stage.equals(main.getStage())
				&& issueStatus.equals(main.getTask().getRollupStatus()) && stage.equals(main.getTask().getRollupStage())) {
			return;
		}
		adopt(main, issueStatus, stage);
	}

	private static String stageFromIssue(String description, String status, String current) {
		if ("已关闭".equals(status)) {
			String declared = IssueStatusText.stageOf(description);
			return declared == null ? IssueStatusText.stageFor(status, current) : declared;
		}
		return IssueStatusText.stageFor(status, current);
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

	private String read(String accessToken, ProjectEntity project, int number) {
		try {
			return gitlab.description(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(),
					number);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
	}

	public record Transition(String status, String stage, boolean synced) {
	}

}
