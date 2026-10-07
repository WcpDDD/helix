package dev.helix.board;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "task")
public class TaskEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private ProjectEntity project;

	@Column(name = "rollup_status", nullable = false)
	private String rollupStatus;

	@Column(name = "rollup_stage", nullable = false)
	private String rollupStage;

	@Column(name = "started_on", nullable = false)
	private LocalDate startedOn;

	@Column(name = "expected_end_on")
	private LocalDate expectedEndOn;

	@Column(name = "finished_on")
	private LocalDate finishedOn;

	protected TaskEntity() {
	}

	public TaskEntity(ProjectEntity project, String rollupStatus, String rollupStage, LocalDate startedOn,
			LocalDate expectedEndOn, LocalDate finishedOn) {
		this.project = project;
		this.rollupStatus = rollupStatus;
		this.rollupStage = rollupStage;
		this.startedOn = startedOn;
		this.expectedEndOn = expectedEndOn;
		this.finishedOn = finishedOn;
	}

	public Long getId() {
		return id;
	}

	public String getRollupStatus() {
		return rollupStatus;
	}

	public String getRollupStage() {
		return rollupStage;
	}

	public void adopt(String status, String stage) {
		this.rollupStatus = status;
		this.rollupStage = stage;
	}

	public LocalDate getStartedOn() {
		return startedOn;
	}

	public LocalDate getExpectedEndOn() {
		return expectedEndOn;
	}

	public LocalDate getFinishedOn() {
		return finishedOn;
	}

}
