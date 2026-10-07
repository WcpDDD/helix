package dev.helix.project;

import java.util.List;

import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {

	private final ProjectRepository projects;

	public ProjectService(ProjectRepository projects) {
		this.projects = projects;
	}

	public List<Project> list() {
		return projects.findAll(Sort.by("id")).stream().map(ProjectService::view).toList();
	}

	public static Project view(ProjectEntity project) {
		return new Project(project.getId(), project.getName(),
				new Project.Workspace(project.getGitHost(), project.getGitOwner() + "/" + project.getGitRepo()));
	}

}
