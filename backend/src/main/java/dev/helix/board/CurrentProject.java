package dev.helix.board;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@Service
public class CurrentProject {

	public static final String SESSION = "helix.project";

	private final ProjectRepository projects;

	public CurrentProject(ProjectRepository projects) {
		this.projects = projects;
	}

	public ProjectEntity resolve(HttpSession session) {
		Long id = read(session);
		if (id != null) {
			return projects.findById(id).orElseGet(this::first);
		}
		return first();
	}

	public ProjectEntity select(HttpSession session, long id) {
		ProjectEntity project = projects.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		session.setAttribute(SESSION, project.getId());
		return project;
	}

	private ProjectEntity first() {
		return projects.findAll(Sort.by("id")).stream().findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static Long read(HttpSession session) {
		if (session == null) {
			return null;
		}
		Object value = session.getAttribute(SESSION);
		if (value instanceof Long id) {
			return id;
		}
		if (value instanceof Integer id) {
			return id.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.valueOf(text);
			}
			catch (NumberFormatException exception) {
				return null;
			}
		}
		return null;
	}

}
