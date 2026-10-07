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

	private final GitlabIssueClient gitlab;

	private final WorkspaceBoard boards;

	public TaskStatusService(IssueRepository issues, GitlabIssueClient gitlab, WorkspaceBoard boards) {
		this.issues = issues;
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
		IssueEntity main = issues.findInProject(projectId, number)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		ProjectEntity project = main.getProject();
		String current = read(accessToken, project, number);
		String stage = IssueStatusText.stageFor(requested, main.getTask().getRollupStage());
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
			return followIssue(accessToken, main);
		}
		adopt(main, requested, stage);
		return new Transition(requested, stage, true);
	}

	private Transition followIssue(String accessToken, IssueEntity main) {
		ProjectEntity project = main.getProject();
		String description = read(accessToken, project, main.getGitIssueNumber());
		String issueStatus = IssueStatusText.statusOf(description);
		if (issueStatus == null) {
			return new Transition(main.getTask().getRollupStatus(), main.getTask().getRollupStage(), false);
		}
		String stage = stageFromIssue(description, issueStatus, main.getTask().getRollupStage());
		adopt(main, issueStatus, stage);
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
