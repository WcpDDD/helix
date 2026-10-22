package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.board.ScheduleLog;
import dev.helix.board.ScheduleLog.ScheduleFile;
import dev.helix.board.ScheduleLog.ScheduleView;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/schedule/logs")
public class ScheduleLogController {

	private final ScheduleLog logs;

	public ScheduleLogController(ScheduleLog logs) {
		this.logs = logs;
	}

	@GetMapping
	public List<ScheduleFile> list(HttpSession session) {
		requireUser(session);
		return logs.files();
	}

	@GetMapping("/{name:.+}")
	public ScheduleView read(@PathVariable String name, @RequestParam(defaultValue = "300") int limit,
			HttpSession session) {
		requireUser(session);
		ScheduleView view = logs.read(name, limit);
		if (view == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return view;
	}

	private static void requireUser(HttpSession session) {
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
	}

}
