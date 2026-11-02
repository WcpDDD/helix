package dev.helix.api;

import dev.helix.auth.AuthController;
import dev.helix.auth.GitlabUser;
import dev.helix.auth.LdapAccountService;
import dev.helix.auth.LdapAccountService.View;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/account/ldap")
public class LdapAccountController {

	private final LdapAccountService accounts;

	public LdapAccountController(LdapAccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping
	public View view(HttpSession session) {
		return accounts.view(require(session).id());
	}

	@PutMapping
	public View save(@RequestBody Draft draft, HttpSession session) {
		return accounts.save(require(session).id(), draft == null ? null : draft.uid(), draft == null ? null : draft.password());
	}

	@DeleteMapping
	public View clear(HttpSession session) {
		return accounts.clear(require(session).id());
	}

	private static GitlabUser require(HttpSession session) {
		if (session == null || !(session.getAttribute(AuthController.SESSION_USER) instanceof GitlabUser user)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return user;
	}

	public record Draft(String uid, String password) {
	}

}
