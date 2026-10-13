package dev.helix.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.IssueSpecText;
import dev.helix.gitlab.IssueSpecText.SpecLinks;
import dev.helix.work.ProjectWorkspaces;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceSpecService {

	private final IssueRepository issues;

	private final GitlabIssueClient gitlab;

	private final ProjectWorkspaces workspaces;

	public WorkspaceSpecService(IssueRepository issues, GitlabIssueClient gitlab, ProjectWorkspaces workspaces) {
		this.issues = issues;
		this.gitlab = gitlab;
		this.workspaces = workspaces;
	}

	public SpecCheckout checkout(String accessToken, long projectId, int number) {
		IssueEntity issue = issues.findInProject(projectId, number)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		ProjectEntity project = issue.getProject();
		String description = readDescription(accessToken, project, number);
		SpecLinks links;
		try {
			links = IssueSpecText.links(description);
		}
		catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		var root = workspaces.require(project, accessToken);
		String branch = workspaces.branch(root);
		if (links.paths().isEmpty()) {
			return new SpecCheckout(branch, "", List.of());
		}
		List<SpecCheckout.SpecFile> files = new ArrayList<>();
		for (String path : links.paths()) {
			List<String> markdown = workspaces.markdownUnder(root, path);
			if (markdown.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			}
			for (String file : markdown) {
				files.add(new SpecCheckout.SpecFile(file, workspaces.read(root, file)));
			}
		}
		files.sort(Comparator.comparing(SpecCheckout.SpecFile::path));
		return new SpecCheckout(branch, workspaces.head(root), List.copyOf(files));
	}

	private String readDescription(String accessToken, ProjectEntity project, int number) {
		try {
			return gitlab.description(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(),
					number);
		}
		catch (GitlabIssueException exception) {
			throw failure(exception);
		}
	}

	private static ResponseStatusException failure(GitlabIssueException exception) {
		if (exception.status() == 401 || exception.status() == 403) {
			return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (exception.status() == 404) {
			return new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

}
