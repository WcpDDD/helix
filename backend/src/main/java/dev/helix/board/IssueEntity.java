package dev.helix.board;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "issue", uniqueConstraints = @UniqueConstraint(columnNames = { "project_id", "git_issue_number" }))
public class IssueEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private ProjectEntity project;

	@ManyToOne(optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private TaskEntity task;

	@Column(name = "is_main", nullable = false)
	private boolean mainIssue;

	@Column(name = "git_issue_number", nullable = false)
	private int gitIssueNumber;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String kind;

	@Column(nullable = false)
	private String stage;

	@Column(nullable = false)
	private String status;

	@ManyToOne(optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private MemberEntity author;

	@ManyToOne(optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private MemberEntity owner;

	@Column(name = "allowed_scope", nullable = false, length = 2000)
	private String allowedScope;

	@Column(name = "blast_radius", nullable = false, length = 2000)
	private String blastRadius;

	@Column(nullable = false, length = 2000)
	private String conclusion;

	@Column(name = "review_summary", nullable = false, length = 2000)
	private String reviewSummary;

	@Column(name = "target_version")
	private String targetVersion;

	@Column(name = "version_confirmed_at")
	private Instant versionConfirmedAt;

	@Column(name = "description_revision", nullable = false)
	private int descriptionRevision;

	@Column(name = "synced_revision", nullable = false)
	private int syncedRevision;

	@Column(name = "synced_at")
	private Instant syncedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected IssueEntity() {
	}

	public IssueEntity(ProjectEntity project, TaskEntity task, boolean mainIssue, int gitIssueNumber, String title,
			String kind, String stage, String status, MemberEntity author, MemberEntity owner, String allowedScope,
			String blastRadius, String conclusion, String reviewSummary, String targetVersion,
			Instant versionConfirmedAt, Instant createdAt) {
		this.project = project;
		this.task = task;
		this.mainIssue = mainIssue;
		this.gitIssueNumber = gitIssueNumber;
		this.title = title;
		this.kind = kind;
		this.stage = stage;
		this.status = status;
		this.author = author;
		this.owner = owner;
		this.allowedScope = allowedScope;
		this.blastRadius = blastRadius;
		this.conclusion = conclusion;
		this.reviewSummary = reviewSummary;
		this.targetVersion = targetVersion;
		this.versionConfirmedAt = versionConfirmedAt;
		this.descriptionRevision = 0;
		this.syncedRevision = 0;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public ProjectEntity getProject() {
		return project;
	}

	public TaskEntity getTask() {
		return task;
	}

	public boolean isMainIssue() {
		return mainIssue;
	}

	public int getGitIssueNumber() {
		return gitIssueNumber;
	}

	public String getTitle() {
		return title;
	}

	public String getKind() {
		return kind;
	}

	public String getStage() {
		return stage;
	}

	public String getStatus() {
		return status;
	}

	public void adoptStatus(String status, String stage) {
		this.status = status;
		this.stage = stage;
		this.updatedAt = Instant.now();
		this.descriptionRevision++;
		this.syncedRevision = this.descriptionRevision;
		this.syncedAt = this.updatedAt;
	}

	public MemberEntity getAuthor() {
		return author;
	}

	public MemberEntity getOwner() {
		return owner;
	}

	public String getAllowedScope() {
		return allowedScope;
	}

	public String getBlastRadius() {
		return blastRadius;
	}

	public String getConclusion() {
		return conclusion;
	}

	public String getReviewSummary() {
		return reviewSummary;
	}

	public String getTargetVersion() {
		return targetVersion;
	}

}
