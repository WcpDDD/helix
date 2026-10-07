package dev.helix.board;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "member", uniqueConstraints = @UniqueConstraint(columnNames = { "project_id", "git_login" }))
public class MemberEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private ProjectEntity project;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "git_login", nullable = false)
	private String gitLogin;

	@Column(name = "gitlab_member", nullable = false, columnDefinition = "integer default 0 not null")
	private boolean gitlabMember;

	@ElementCollection
	@CollectionTable(name = "member_role", joinColumns = @JoinColumn(name = "member_id"))
	@Column(name = "role", nullable = false)
	private Set<String> roles = new LinkedHashSet<>();

	protected MemberEntity() {
	}

	public MemberEntity(ProjectEntity project, String displayName, String gitLogin) {
		this.project = project;
		this.displayName = displayName;
		this.gitLogin = gitLogin;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getGitLogin() {
		return gitLogin;
	}

	public void rename(String name) {
		if (name == null) {
			return;
		}
		String text = name.trim();
		if (text.isEmpty()) {
			return;
		}
		this.displayName = text.length() > 80 ? text.substring(0, 80) : text;
	}

	public void markFromGitlab(boolean present) {
		this.gitlabMember = present;
	}

	public boolean isGitlabMember() {
		return gitlabMember;
	}

	public void assignRoles(Collection<String> next) {
		roles.clear();
		roles.addAll(next);
	}

	public Set<String> getRoles() {
		return Set.copyOf(roles);
	}

}
