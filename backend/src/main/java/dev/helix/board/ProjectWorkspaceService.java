package dev.helix.board;

import java.net.URI;
import java.util.List;

import dev.helix.config.HelixProperties;
import dev.helix.gitlab.GitlabIssueException;
import dev.helix.gitlab.GitlabProjectClient;
import dev.helix.gitlab.GitlabProjectRef;
import dev.helix.project.Project;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectWorkspaceService {

	private final ProjectRepository projects;

	private final IssueRepository issues;

	private final GitlabProjectClient gitlab;

	private final HelixProperties properties;

	private final TransactionTemplate transactions;

	public ProjectWorkspaceService(ProjectRepository projects, IssueRepository issues, GitlabProjectClient gitlab,
			HelixProperties properties, TransactionTemplate transactions) {
		this.projects = projects;
		this.issues = issues;
		this.gitlab = gitlab;
		this.properties = properties;
		this.transactions = transactions;
	}

	public List<GitlabProjectRef> gitlabProjects(String accessToken, String search) {
		try {
			return gitlab.membership(accessToken, origin(), search);
		}
		catch (GitlabIssueException exception) {
			throw translated(exception);
		}
	}

	public Project save(long id, String name, String path, String accessToken) {
		Located located = locate(name, path, accessToken);
		ProjectEntity saved = transactions.execute(status -> write(id, located));
		if (saved == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return dev.helix.project.ProjectService.view(saved);
	}

	public Project create(String name, String path, String accessToken) {
		Located located = locate(name, path, accessToken);
		ProjectEntity saved = transactions.execute(status -> insert(located));
		if (saved == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		return dev.helix.project.ProjectService.view(saved);
	}

	private Located locate(String name, String path, String accessToken) {
		String requested = pathOf(path);
		GitlabProjectRef remote;
		try {
			remote = gitlab.find(accessToken, origin(), requested);
		}
		catch (GitlabIssueException exception) {
			throw translated(exception);
		}
		String resolved = remote.path();
		int slash = resolved.lastIndexOf('/');
		if (slash <= 0 || slash == resolved.length() - 1) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
		String owner = resolved.substring(0, slash);
		String repo = resolved.substring(slash + 1);
		String chosen = name == null || name.isBlank() ? remote.name() : name.trim();
		String display = chosen.isBlank() ? repo : chosen;
		String host = URI.create(origin()).getHost();
		if (host == null || host.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
		return new Located(display, host, owner, repo, resolved);
	}

	private ProjectEntity insert(Located located) {
		if (taken(null, located)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		return projects.save(new ProjectEntity(located.display(), located.host(), located.owner(), located.repo()));
	}

	private ProjectEntity write(long id, Located located) {
		ProjectEntity project = projects.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String current = project.getGitOwner() + "/" + project.getGitRepo();
		if (!current.equals(located.path()) && issues.countByProject_Id(project.getId()) > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		if (taken(project.getId(), located)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT);
		}
		project.place(located.display(), located.host(), located.owner(), located.repo());
		return projects.save(project);
	}

	private boolean taken(Long exceptId, Located located) {
		return projects.findAll(Sort.by("id")).stream()
				.filter(other -> exceptId == null || !other.getId().equals(exceptId))
				.anyMatch(other -> located.host().equals(other.getGitHost()) && located.owner().equals(other.getGitOwner())
						&& located.repo().equals(other.getGitRepo()));
	}

	private record Located(String display, String host, String owner, String repo, String path) {
	}

	private String pathOf(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.startsWith("http://") || value.startsWith("https://")) {
			URI uri;
			try {
				uri = URI.create(value);
			}
			catch (IllegalArgumentException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			}
			String host = uri.getHost();
			String expected = URI.create(origin()).getHost();
			if (host == null || expected == null || !host.equalsIgnoreCase(expected)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			}
			value = uri.getPath() == null ? "" : uri.getPath();
		}
		while (value.startsWith("/")) {
			value = value.substring(1);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.endsWith(".git")) {
			value = value.substring(0, value.length() - 4);
		}
		int extra = value.indexOf("/-/");
		if (extra >= 0) {
			value = value.substring(0, extra);
		}
		if (!value.contains("/") || value.contains(" ") || value.contains("..")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		return value;
	}

	private String origin() {
		return properties.gitlab().origin();
	}

	private static ResponseStatusException translated(GitlabIssueException exception) {
		int status = exception.status();
		if (status == 401 || status == 403) {
			return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (status == 404) {
			return new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

}
