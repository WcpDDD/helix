package dev.helix.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.helix.gitlab.GitlabDirectory;
import dev.helix.gitlab.GitlabDirectoryClient;
import dev.helix.gitlab.GitlabIssueException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberSyncService {

	private final ProjectRepository projects;

	private final MemberRepository members;

	private final MemberTeamRepository teams;

	private final MemberRoleService roles;

	private final GitlabDirectoryClient gitlab;

	private final TransactionTemplate transactions;

	public MemberSyncService(ProjectRepository projects, MemberRepository members, MemberTeamRepository teams,
			MemberRoleService roles, GitlabDirectoryClient gitlab, TransactionTemplate transactions) {
		this.projects = projects;
		this.members = members;
		this.teams = teams;
		this.roles = roles;
		this.gitlab = gitlab;
		this.transactions = transactions;
	}

	public MemberBoard board(ProjectEntity project) {
		return transactions.execute(status -> assemble(project));
	}

	public MemberBoard sync(String accessToken, ProjectEntity project) {
		ProjectRef ref = new ProjectRef(project.getId(), project.getGitHost(), project.getGitOwner(), project.getGitRepo());
		GitlabDirectory directory;
		try {
			directory = gitlab.load(accessToken, ref.host(), ref.owner(), ref.repo());
		}
		catch (GitlabIssueException exception) {
			throw translated(exception);
		}
		return transactions.execute(status -> {
			apply(ref.id(), directory);
			return assemble(projects.findById(ref.id())
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
		});
	}

	private void apply(Long projectId, GitlabDirectory directory) {
		ProjectEntity project = projects.findById(projectId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		List<MemberEntity> existing = members.findForProject(projectId);
		Map<String, MemberEntity> byLogin = new LinkedHashMap<>();
		for (MemberEntity member : existing) {
			byLogin.put(member.getGitLogin(), member);
		}
		Set<String> seen = new LinkedHashSet<>();
		for (GitlabDirectory.Person person : directory.people()) {
			MemberEntity member = byLogin.get(person.username());
			if (member == null) {
				member = members.save(new MemberEntity(project, displayName(person), person.username()));
				byLogin.put(person.username(), member);
			}
			else {
				member.rename(person.name());
			}
			member.markFromGitlab(true);
			seen.add(person.username());
		}
		for (MemberEntity member : existing) {
			if (!seen.contains(member.getGitLogin())) {
				member.markFromGitlab(false);
			}
		}
		teams.deleteForProject(projectId);
		Map<String, String> teamNames = new LinkedHashMap<>();
		for (GitlabDirectory.Team team : directory.teams()) {
			teamNames.put(team.id(), team.name());
		}
		for (GitlabDirectory.Person person : directory.people()) {
			MemberEntity member = byLogin.get(person.username());
			for (String teamId : person.teamIds()) {
				teams.save(new MemberTeamEntity(member, teamId, teamNames.getOrDefault(teamId, teamId)));
			}
		}
	}

	private MemberBoard assemble(ProjectEntity project) {
		List<String> labels = roles.list(project).labels();
		List<MemberEntity> rows = members.findForProject(project.getId());
		Map<String, List<String>> teamsByLogin = new LinkedHashMap<>();
		Map<String, String> teamNames = new LinkedHashMap<>();
		for (MemberTeamEntity team : teams.findForProject(project.getId())) {
			MemberEntity member = team.getMember();
			if (!member.isGitlabMember()) {
				continue;
			}
			teamsByLogin.computeIfAbsent(member.getGitLogin(), ignored -> new ArrayList<>()).add(team.getTeamPath());
			teamNames.putIfAbsent(team.getTeamPath(), team.getTeamName());
		}
		List<PersonView> people = new ArrayList<>();
		for (MemberEntity member : rows) {
			List<String> assigned = labels.stream().filter(member.getRoles()::contains).toList();
			if (!member.isGitlabMember() && assigned.isEmpty()) {
				continue;
			}
			List<String> teamIds = member.isGitlabMember()
					? teamsByLogin.getOrDefault(member.getGitLogin(), List.of())
					: List.of();
			people.add(new PersonView(member.getGitLogin(), member.getDisplayName(), teamIds, assigned));
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (PersonView person : people) {
			for (String teamId : person.teamIds()) {
				counts.merge(teamId, 1, Integer::sum);
			}
		}
		List<TeamView> teamViews = teamNames.entrySet().stream()
				.map(entry -> new TeamView(entry.getKey(), entry.getValue()))
				.sorted(Comparator.comparingInt((TeamView team) -> counts.getOrDefault(team.id(), 0)).reversed()
						.thenComparing(TeamView::name))
				.toList();
		return new MemberBoard(teamViews, people, labels);
	}

	private static String displayName(GitlabDirectory.Person person) {
		String name = person.name() == null ? "" : person.name().trim();
		if (name.isEmpty()) {
			return person.username();
		}
		return name.length() > 80 ? name.substring(0, 80) : name;
	}

	private static ResponseStatusException translated(GitlabIssueException exception) {
		if (exception.status() == 401 || exception.status() == 403) {
			return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

	private record ProjectRef(Long id, String host, String owner, String repo) {
	}

	public record MemberBoard(List<TeamView> teams, List<PersonView> members, List<String> labels) {
	}

	public record TeamView(String id, String name) {
	}

	public record PersonView(String login, String name, List<String> teamIds, List<String> roles) {
	}

}
