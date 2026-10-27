package dev.helix.worker;

import java.util.ArrayList;
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

	public String comment(String workerId, int number, String text) {
		if (text == null || text.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评论不能空。");
		}
		Bound bound = bound(workerId, number);
		try {
			GitlabComment note = gitlab.comment(bound.token(), bound.project().getGitHost(), bound.project().getGitOwner(),
					bound.project().getGitRepo(), number, text.strip());
			ObjectNode body = json.createObjectNode();
			body.put("id", note.id());
			body.put("body", note.body() == null ? "" : note.body());
			return json.writeValueAsString(body);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "没有登录。");
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "评论没有提交。");
		}
	}

	public String read(String workerId, int number) {
		Bound bound = bound(workerId, number);
		String token = bound.token();
		ProjectEntity project = bound.project();
		GitlabIssueClient.IssueDetail detail;
		List<GitlabComment> comments;
		try {
			detail = gitlab.detail(token, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), number);
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
		body.put("description", detail.description() == null ? "" : detail.description());
		ArrayNode labels = body.putArray("labels");
		detail.labels().forEach(labels::add);
		ArrayNode notes = body.putArray("comments");
		if (comments != null) {
			for (GitlabComment comment : comments) {
				ObjectNode note = notes.addObject();
				note.put("id", comment.id());
				note.put("body", comment.body() == null ? "" : comment.body());
			}
		}
		ArrayNode mrs = body.putArray("mrs");
		for (MergeRequestRef ref : mergeRequestsIn(detail.description(), project.getGitHost())) {
			ObjectNode mr = mrs.addObject();
			mr.put("repo", ref.owner() + "/" + ref.repo());
			mr.put("iid", ref.iid());
			mr.put("url", ref.url());
			try {
				GitlabIssueClient.MrDetail found = gitlab.mergeRequest(token, project.getGitHost(), ref.owner(),
						ref.repo(), ref.iid());
				mr.put("title", found.title());
				mr.put("state", found.state());
				mr.put("sourceBranch", found.sourceBranch());
				mr.put("sha", found.sha());
				mr.put("pipeline", found.pipeline());
				mr.put("changes", found.changes());
				ArrayNode files = mr.putArray("files");
				found.files().forEach(files::add);
			}
			catch (GitlabIssueException exception) {
				mr.put("error", exception.status() == 404 ? "MR 不存在" : "MR 没有读到");
			}
		}
		return json.writeValueAsString(body);
	}

	private static final java.util.regex.Pattern MR_URL = java.util.regex.Pattern
			.compile("https?://([^/\\s]+)/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/-/merge_requests/(\\d+)");

	private record MergeRequestRef(String owner, String repo, int iid, String url) {
	}

	static List<MergeRequestRef> mergeRequestsIn(String description, String gitHost) {
		List<MergeRequestRef> refs = new ArrayList<>();
		if (description == null || description.isBlank()) {
			return refs;
		}
		String host = gitHost == null ? "" : gitHost.replaceFirst("^https?://", "").replaceFirst("/+$", "");
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.regex.Matcher matcher = MR_URL.matcher(description);
		while (matcher.find() && refs.size() < 5) {
			if (!host.isBlank() && !host.equals(matcher.group(1))) {
				continue;
			}
			String owner = matcher.group(2);
			String repo = matcher.group(3);
			int iid = Integer.parseInt(matcher.group(4));
			if (!seen.add(owner + "/" + repo + "!" + iid)) {
				continue;
			}
			refs.add(new MergeRequestRef(owner, repo, iid, matcher.group(0)));
		}
		return refs;
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
