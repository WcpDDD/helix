package dev.helix.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.worker.AgentPresence;
import dev.helix.gitlab.GitlabIssueClient.OpenedIssue;
import dev.helix.gitlab.GitlabIssueException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoalService {

	private final GitlabIssueClient gitlab;

	private final ManagerAnalysis managers;

	private final MockRuntime runtime;

	private final AgentPresence presence;

	public GoalService(GitlabIssueClient gitlab, ManagerAnalysis managers, MockRuntime runtime, AgentPresence presence) {
		this.gitlab = gitlab;
		this.managers = managers;
		this.runtime = runtime;
		this.presence = presence;
	}

	public GoalView start(String token, ProjectEntity project, int iid, String goal) {
		String text = goal == null || goal.isBlank() ? "做到测试通过" : goal.strip();
		try {
			gitlab.addLabel(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), iid, ProposalDraft.AI);
			managers.analyze(token, project, iid, withManaged(token, project, iid), text);
		}
		catch (GitlabIssueException exception) {
			throw failed(exception);
		}
		return view(token, project, iid);
	}

	public GoalView status(String token, ProjectEntity project, int iid) {
		return view(token, project, iid);
	}

	public void stop(String token, ProjectEntity project, int iid) {
		try {
			gitlab.removeLabel(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), iid, ProposalDraft.AI);
		}
		catch (GitlabIssueException exception) {
			throw failed(exception);
		}
	}

	private GoalView view(String token, ProjectEntity project, int iid) {
		List<String> labels = labels(token, project, iid);
		if (labels.contains(ProposalDraft.HITL)) {
			return new GoalView("confirm", "交给你确认。");
		}
		Optional<AgentPresence.Run> live = presence.running(iid);
		if (live.isPresent()) {
			return new GoalView("turn", "有 agent 正在做。");
		}
		if (project.getId() != null) {
			Optional<MockRuntime.Lease> busy = runtime.running(project.getId(), iid);
			if (busy.isPresent()) {
				return new GoalView("turn", busy.get().tag() + " 正在做。");
			}
		}
		if (labels.contains(ProposalDraft.AI)) {
			return new GoalView("turn", "manager 正在看 issue 和 MR，决定下一步派谁。");
		}
		return new GoalView("idle", "");
	}

	private List<String> withManaged(String token, ProjectEntity project, int iid) {
		List<String> labels = new ArrayList<>(labels(token, project, iid));
		if (!labels.contains(ProposalDraft.AI)) {
			labels.add(ProposalDraft.AI);
		}
		return labels;
	}

	private List<String> labels(String token, ProjectEntity project, int iid) {
		try {
			for (OpenedIssue issue : gitlab.opened(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo())) {
				if (issue.iid() == iid) {
					return issue.labels() == null ? List.of() : issue.labels();
				}
			}
		}
		catch (GitlabIssueException exception) {
			return List.of();
		}
		return List.of();
	}

	private static ResponseStatusException failed(GitlabIssueException exception) {
		int status = exception.status();
		if (status == 401 || status == 403) {
			return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (status == 404) {
			return new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

	public record GoalView(String phase, String note) {
	}

}
