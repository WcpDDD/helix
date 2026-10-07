package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.CurrentProject;
import dev.helix.board.ProjectWorkspaceService;
import dev.helix.gitlab.GitlabProjectRef;
import dev.helix.project.Project;
import dev.helix.project.ProjectService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api")
public class ProjectController {

	private final ProjectService projectService;

	private final ProjectWorkspaceService workspace;

	private final CurrentProject currentProject;

	public ProjectController(ProjectService projectService, ProjectWorkspaceService workspace, CurrentProject currentProject) {
		this.projectService = projectService;
		this.workspace = workspace;
		this.currentProject = currentProject;
	}

	@GetMapping("/projects")
	public List<Project> list() {
		return projectService.list();
	}

	@GetMapping("/projects/current")
	public Project current(HttpServletRequest http) {
		return ProjectService.view(currentProject.resolve(http.getSession(false)));
	}

	@PutMapping("/projects/current")
	public Project use(@RequestBody IdRequest request, HttpServletRequest http) {
		requireUser(http);
		long id = request == null ? 0 : request.id();
		return ProjectService.view(currentProject.select(http.getSession(true), id));
	}

	@PostMapping("/projects")
	public Project create(@RequestBody SaveRequest request, HttpServletRequest http) {
		requireUser(http);
		String path = request == null ? null : request.path();
		String name = request == null ? null : request.name();
		Project created = workspace.create(name, path, token(http));
		currentProject.select(http.getSession(true), created.id());
		return created;
	}

	@GetMapping("/gitlab/projects")
	public List<GitlabProjectRef> gitlab(@RequestParam(name = "q", required = false) String query, HttpServletRequest http) {
		return workspace.gitlabProjects(token(http), query);
	}

	@PutMapping("/projects/{id}")
	public Project save(@PathVariable long id, @RequestBody SaveRequest request, HttpServletRequest http) {
		String path = request == null ? null : request.path();
		String name = request == null ? null : request.name();
		return workspace.save(id, name, path, token(http));
	}

	private static String token(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		Object token = session == null ? null : session.getAttribute(AuthController.SESSION_TOKEN);
		if (!(token instanceof String accessToken) || accessToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return accessToken;
	}

	public record SaveRequest(String name, String path) {
	}

	public record IdRequest(long id) {
	}

	private static void requireUser(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
	}

}
