package dev.helix.api;

import java.util.List;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabSessionToken;
import dev.helix.board.CurrentProject;
import dev.helix.board.IssueBodyService;
import dev.helix.board.ProjectEntity;
import dev.helix.gitlab.GitlabComment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/issues")
public class IssueBodyController {

	private final IssueBodyService issueBodyService;

	private final CurrentProject currentProject;

	private final GitlabSessionToken tokens;

	public IssueBodyController(IssueBodyService issueBodyService, CurrentProject currentProject,
			GitlabSessionToken tokens) {
		this.issueBodyService = issueBodyService;
		this.currentProject = currentProject;
		this.tokens = tokens;
	}

	@GetMapping("/{number}/body")
	public IssueBody body(@PathVariable int number, HttpSession session) {
		ProjectEntity project = currentProject.resolve(session);
		return new IssueBody(issueBodyService.body(project, number, tokens.require(session)));
	}

	@GetMapping("/{number}/notes")
	public IssueNotes notes(@PathVariable int number, HttpSession session) {
		ProjectEntity project = currentProject.resolve(session);
		List<IssueNotes.Note> notes = issueBodyService.comments(project, number, tokens.require(session)).stream()
				.map(IssueBodyController::note)
				.toList();
		return new IssueNotes(notes);
	}

	private static IssueNotes.Note note(GitlabComment comment) {
		return new IssueNotes.Note(comment.id(), comment.author(), comment.createdAt(), comment.body());
	}

	public record IssueBody(String body) {
	}

	public record IssueNotes(List<Note> notes) {

		public record Note(long id, String author, String createdAt, String body) {
		}

	}

}
