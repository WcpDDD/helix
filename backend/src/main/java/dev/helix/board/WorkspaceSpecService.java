package dev.helix.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.GitlabRepositoryClient;
import dev.helix.gitlab.IssueSpecText;
import dev.helix.gitlab.IssueSpecText.SpecLinks;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceSpecService {

	private final IssueRepository issues;

	private final GitlabIssueClient gitlab;

	private final GitlabRepositoryClient repository;

	public WorkspaceSpecService(IssueRepository issues, GitlabIssueClient gitlab, GitlabRepositoryClient repository) {
		this.issues = issues;
		this.gitlab = gitlab;
		this.repository = repository;
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
		if (links.paths().isEmpty()) {
			return new SpecCheckout(links.branch() == null ? "" : links.branch(), "", List.of());
		}
		String branch = links.branch();
		if (branch == null || branch.isBlank()) {
			branch = repository.defaultBranch(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo());
		}
		String sha = readCommit(accessToken, project, branch);
		List<SpecCheckout.SpecFile> files = new ArrayList<>();
		for (String path : links.paths()) {
			for (String file : filesAt(accessToken, project, branch, path)) {
				String markdown = readFile(accessToken, project, file, branch);
				files.add(new SpecCheckout.SpecFile(file, markdown));
			}
		}
		files.sort(Comparator.comparing(SpecCheckout.SpecFile::path));
		return new SpecCheckout(branch, sha.length() <= 7 ? sha : sha.substring(0, 7), List.copyOf(files));
	}

	private List<String> filesAt(String accessToken, ProjectEntity project, String branch, String path) {
		try {
			List<String> blobs = repository.blobs(accessToken, project.getGitHost(), project.getGitOwner(),
					project.getGitRepo(), path, branch);
			if (blobs == null) {
				return List.of(path);
			}
			List<String> markdown = blobs.stream().filter(item -> item.endsWith(".md")).toList();
			if (!markdown.isEmpty()) {
				return markdown;
			}
			if (path.endsWith(".md")) {
				return List.of(path);
			}
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		catch (GitlabIssueException exception) {
			throw failure(exception);
		}
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

	private String readCommit(String accessToken, ProjectEntity project, String branch) {
		try {
			return repository.commitSha(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(),
					branch);
		}
		catch (GitlabIssueException exception) {
			throw failure(exception);
		}
	}

	private String readFile(String accessToken, ProjectEntity project, String path, String branch) {
		try {
			return repository.fileAt(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo(), path,
					branch);
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
