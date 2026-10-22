package dev.helix.worker;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.board.ProposalDraft;
import dev.helix.gitlab.GitlabComment;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkerIssueRead {

	private final WorkerRegistry registry;

	private final AgentSessionRepository sessions;

	private final ProjectRepository projects;

	private final GitlabIssueClient gitlab;

	private final GitlabSessionCredential credential;

	private final JsonMapper json = new JsonMapper();

	public WorkerIssueRead(WorkerRegistry registry, AgentSessionRepository sessions, ProjectRepository projects,
			GitlabIssueClient gitlab, GitlabSessionCredential credential) {
		this.registry = registry;
		this.sessions = sessions;
		this.projects = projects;
		this.gitlab = gitlab;
		this.credential = credential;
	}

	public void hitl(String workerId, int number) {
		Bound bound = bound(workerId, number);
		try {
			gitlab.addLabel(bound.token(), bound.project().getGitHost(), bound.project().getGitOwner(),
					bound.project().getGitRepo(), number, ProposalDraft.HITL);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "没有登录。");
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "待办没有派给人。");
		}
	}

	public void unmanage(String workerId, int number) {
		Bound bound = bound(workerId, number);
		try {
			gitlab.removeLabel(bound.token(), bound.project().getGitHost(), bound.project().getGitOwner(),
					bound.project().getGitRepo(), number, ProposalDraft.AI);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "没有登录。");
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "托管标签没有摘掉。");
		}
	}

	public String read(String workerId, int number) {
		Bound bound = bound(workerId, number);
		String token = bound.token();
		ProjectEntity project = bound.project();
		String description;
		List<GitlabComment> comments;
		try {
			description = gitlab.description(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), number);
			comments = gitlab.comments(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), number);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "没有登录。");
			}
			if (exception.status() == 404) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "issue 没有读到。");
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "issue 没有读到。");
		}
		ObjectNode body = json.createObjectNode();
		body.put("description", description == null ? "" : description);
		ArrayNode notes = body.putArray("comments");
		if (comments != null) {
			for (GitlabComment comment : comments) {
				ObjectNode note = notes.addObject();
				note.put("id", comment.id());
				note.put("body", comment.body() == null ? "" : comment.body());
			}
		}
		return json.writeValueAsString(body);
	}

	private Bound bound(String workerId, int number) {
		if (number <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issue 编号不对。");
		}
		registry.live(workerId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "这个 worker 不存活。"));
		long projectId = sessions.findByWorkerIdAndIssueNumber(workerId, number).stream()
				.findFirst()
				.map(AgentSessionEntity::getProjectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这张 issue 不在这个 worker 上。"));
		ProjectEntity project = projects.findById(projectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这张 issue 不在这个 worker 上。"));
		String token = credential.current();
		if (token == null || token.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "没有登录。");
		}
		return new Bound(project, token);
	}

	private record Bound(ProjectEntity project, String token) {
	}

}
