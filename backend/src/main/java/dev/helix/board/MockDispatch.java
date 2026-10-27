package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.OpenedIssue;
import dev.helix.gitlab.GitlabIssueException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 旧的模拟派发。不再接入调度。不改 issue 状态。
 */
@Component
public class MockDispatch {

	private final GitlabIssueClient gitlab;

	private final GitlabSessionCredential credential;

	private final MockRuntime runtime;

	private final JsonMapper json = new JsonMapper();

	public MockDispatch(GitlabIssueClient gitlab, GitlabSessionCredential credential, MockRuntime runtime) {
		this.gitlab = gitlab;
		this.credential = credential;
		this.runtime = runtime;
	}

	public String answer(AgentEntity agent, int iid) {
		long projectId = agent.getProject().getId() == null ? 0L : agent.getProject().getId();
		MockRuntime.Hold hold = runtime.hold(projectId, iid, agent);
		if (!hold.started()) {
			return running(agent.getTag(), hold.lease());
		}
		try {
			return work(agent, iid, hold);
		}
		catch (RuntimeException exception) {
			runtime.release(projectId, iid, hold.lease());
			throw exception;
		}
	}

	private String work(AgentEntity agent, int iid, MockRuntime.Hold hold) {
		String token = credential.current();
		if (token == null || token.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "没有登录。");
		}
		ProjectEntity project = agent.getProject();
		String description;
		List<GitlabComment> comments;
		try {
			description = gitlab.description(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), iid);
			comments = gitlab.comments(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), iid);
		}
		catch (GitlabIssueException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "issue 没有读到。");
		}
		if (description == null) {
			description = "";
		}
		List<String> labels = labels(token, project, iid);
		List<ManagerRound.Note> notes = notes(comments);
		String status = ManagerRound.shown(description);
		List<String> skills = agent.getSkills().stream().map(skill -> skill.getSkillPath()).toList();
		MockAgent.Turn turn = MockAgent.run(new ManagerRound.Face(agent.getTag(), agent.getName(), agent.getBody(), skills),
				description, labels, status, notes);
		String written = StatusLock.keep(description, turn.description());
		if (!written.equals(description)) {
			try {
				gitlab.writeDescription(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), iid, written,
						false);
			}
			catch (GitlabIssueException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "issue 没有写回。");
			}
		}
		String text = turn.text() + " 状态仍是 " + status + "。运行到 " + MockRuntime.format(hold.lease().until()) + "。";
		return body(turn.tag(), text, status, hold.lease());
	}

	private String running(String tag, MockRuntime.Lease lease) {
		return body(tag, "还在运行，到 " + MockRuntime.format(lease.until()) + "。", "", lease);
	}

	private String body(String tag, String text, String status, MockRuntime.Lease lease) {
		ObjectNode body = json.createObjectNode();
		body.put("tag", tag == null ? "" : tag);
		body.put("text", text);
		body.put("running", true);
		body.put("until", MockRuntime.format(lease.until()));
		if (status != null && !status.isBlank()) {
			body.put("status", status);
		}
		return json.writeValueAsString(body);
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

	private static List<ManagerRound.Note> notes(List<GitlabComment> comments) {
		List<ManagerRound.Note> notes = new ArrayList<>();
		if (comments == null) {
			return notes;
		}
		for (GitlabComment comment : comments) {
			notes.add(new ManagerRound.Note(comment.id(), comment.body()));
		}
		return notes;
	}

}
