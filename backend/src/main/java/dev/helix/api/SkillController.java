package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.board.CurrentProject;
import dev.helix.board.SkillIndex;
import dev.helix.board.WorkspaceSkillService;
import dev.helix.board.WorkspaceSkillService.SkillFile;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

	private final WorkspaceSkillService skills;

	private final CurrentProject currentProject;

	public SkillController(WorkspaceSkillService skills, CurrentProject currentProject) {
		this.skills = skills;
		this.currentProject = currentProject;
	}

	@GetMapping
	public List<SkillIndex.SkillView> list(HttpSession session) {
		return skills.list(token(session), currentProject.resolve(session));
	}

	@GetMapping("/file")
	public SkillFile file(@RequestParam String path, HttpSession session) {
		return skills.file(token(session), currentProject.resolve(session), path);
	}

	private static String token(HttpSession session) {
		Object token = session == null ? null : session.getAttribute(AuthController.SESSION_TOKEN);
		if (!(token instanceof String accessToken) || accessToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return accessToken;
	}

}
