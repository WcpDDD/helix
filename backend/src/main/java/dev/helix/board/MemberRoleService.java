package dev.helix.board;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberRoleService {

	public static final List<String> DEFAULTS = List.of("前端", "后端", "测试", "产品", "架构师");

	private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	private final MemberRepository members;

	private final MemberLabelRepository labels;

	public MemberRoleService(MemberRepository members, MemberLabelRepository labels) {
		this.members = members;
		this.labels = labels;
	}

	@Transactional
	public MemberDirectory list(ProjectEntity project) {
		List<String> catalog = catalog(project);
		List<MemberRoleView> views = new ArrayList<>();
		for (MemberEntity member : members.findForProject(project.getId())) {
			List<String> roles = ordered(member.getRoles(), catalog);
			if (!roles.isEmpty()) {
				views.add(new MemberRoleView(member.getGitLogin(), roles));
			}
		}
		return new MemberDirectory(catalog, views);
	}

	@Transactional
	public List<String> create(ProjectEntity project, String name) {
		String label = clean(name);
		List<String> catalog = catalog(project);
		if (!catalog.contains(label)) {
			labels.save(new MemberLabelEntity(project, label));
		}
		return catalog(project);
	}

	@Transactional
	public MemberRoleView assign(ProjectEntity project, String login, String displayName, List<String> requested) {
		if (login == null || !LOGIN.matcher(login).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		List<String> catalog = catalog(project);
		List<String> roles = normalize(requested, catalog);
		MemberEntity member = members.findByProject_IdAndGitLogin(project.getId(), login)
				.orElseGet(() -> newMember(project, login, displayName));
		member.assignRoles(roles);
		members.save(member);
		return new MemberRoleView(member.getGitLogin(), ordered(member.getRoles(), catalog));
	}

	private List<String> catalog(ProjectEntity project) {
		ensureLabels(project);
		return labels.findByProject_IdOrderByIdAsc(project.getId()).stream().map(MemberLabelEntity::getName).toList();
	}

	private void ensureLabels(ProjectEntity project) {
		if (!labels.findByProject_IdOrderByIdAsc(project.getId()).isEmpty()) {
			return;
		}
		for (String name : DEFAULTS) {
			labels.save(new MemberLabelEntity(project, name));
		}
	}

	private MemberEntity newMember(ProjectEntity project, String login, String name) {
		String displayName = name == null ? "" : name.trim();
		if (displayName.isEmpty() || displayName.length() > 80) {
			displayName = login;
		}
		return new MemberEntity(project, displayName, login);
	}

	private static List<String> normalize(List<String> requested, List<String> catalog) {
		if (requested == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		Set<String> chosen = new LinkedHashSet<>();
		for (String role : requested) {
			if (!catalog.contains(role)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			}
			chosen.add(role);
		}
		return ordered(chosen, catalog);
	}

	private static String clean(String name) {
		String text = name == null ? "" : name.trim();
		if (text.isEmpty() || text.length() > 20 || "未设置".equals(text) || text.chars().anyMatch(Character::isISOControl)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		return text;
	}

	private static List<String> ordered(Set<String> roles, List<String> catalog) {
		return catalog.stream().filter(roles::contains).toList();
	}

	public record MemberDirectory(List<String> labels, List<MemberRoleView> members) {
	}

	public record MemberRoleView(String login, List<String> roles) {
	}

}
