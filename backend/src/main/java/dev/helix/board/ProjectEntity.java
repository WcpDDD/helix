package dev.helix.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "project", uniqueConstraints = @UniqueConstraint(columnNames = { "git_host", "git_owner", "git_repo" }))
public class ProjectEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(name = "git_host", nullable = false)
	private String gitHost;

	@Column(name = "git_owner", nullable = false)
	private String gitOwner;

	@Column(name = "git_repo", nullable = false)
	private String gitRepo;

	protected ProjectEntity() {
	}

	public ProjectEntity(String name, String gitHost, String gitOwner, String gitRepo) {
		this.name = name;
		this.gitHost = gitHost;
		this.gitOwner = gitOwner;
		this.gitRepo = gitRepo;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getGitHost() {
		return gitHost;
	}

	public String getGitOwner() {
		return gitOwner;
	}

	public String getGitRepo() {
		return gitRepo;
	}

	public void place(String name, String gitHost, String gitOwner, String gitRepo) {
		this.name = name;
		this.gitHost = gitHost;
		this.gitOwner = gitOwner;
		this.gitRepo = gitRepo;
	}

}
