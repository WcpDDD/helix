package dev.helix.board;

import java.util.List;
import java.util.Map;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.CreatedIssue;
import dev.helix.gitlab.GitlabIssueClient.UploadedFile;
import dev.helix.gitlab.GitlabIssueException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskCreateService {

	private final GitlabIssueClient gitlab;

	private final ProposalHandoff proposals;

	private final IssueListCache issueLists;

	private final WorkspaceBoard boards;

	public TaskCreateService(GitlabIssueClient gitlab, ProposalHandoff proposals, IssueListCache issueLists,
			WorkspaceBoard boards) {
		this.gitlab = gitlab;
		this.proposals = proposals;
		this.issueLists = issueLists;
		this.boards = boards;
	}

	public CreatedIssue create(String accessToken, ProjectEntity project, String title, String kind,
			Map<String, String> fields, List<String> attachments, boolean delegate, String author) {
		String heading = title == null ? "" : title.strip();
		if (heading.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要写标题。");
		}
		if (ProposalDraft.fields(kind) == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "类型不对。");
		}
		List<String> missing = ProposalDraft.missing(kind, fields);
		if (!missing.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "还要写：" + String.join("、", missing) + "。");
		}
		if (delegate) {
			proposals.ensure(project);
		}
		String description = ProposalDraft.description(kind, fields, attachments);
		List<String> labels = ProposalDraft.labels(kind);
		try {
			CreatedIssue created = gitlab.createIssue(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), heading, description, labels);
			issueLists.insert(project.getId(), new GitlabIssueClient.IssueHead(created.iid(), heading, description,
					author == null ? "" : author, "", "opened", labels));
			boards.record(project, accessToken, created.iid(), author, null);
			if (delegate) {
				proposals.hand(project, created.iid());
			}
			return created;
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "issue 没有建起来。");
		}
	}

	public UploadedFile upload(String accessToken, ProjectEntity project, String filename, byte[] content) {
		String name = filename(filename);
		if (name.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不对。");
		}
		if (content == null || content.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件是空的。");
		}
		try {
			return gitlab.upload(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), name,
					content);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 401 || exception.status() == 403) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "附件没有传上去。");
		}
	}

	static String filename(String raw) {
		if (raw == null) {
			return "";
		}
		String name = raw.replace('\\', '/');
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		name = name.strip();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
			return "";
		}
		if (name.length() > 180) {
			name = name.substring(name.length() - 180);
		}
		return name;
	}

}
