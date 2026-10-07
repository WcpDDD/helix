package dev.helix.board;

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
@Table(name = "member_team", uniqueConstraints = @UniqueConstraint(columnNames = { "member_id", "team_path" }))
public class MemberTeamEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private MemberEntity member;

	@Column(name = "team_path", nullable = false)
	private String teamPath;

	@Column(name = "team_name", nullable = false)
	private String teamName;

	protected MemberTeamEntity() {
	}

	public MemberTeamEntity(MemberEntity member, String teamPath, String teamName) {
		this.member = member;
		this.teamPath = teamPath;
		this.teamName = teamName;
	}

	public MemberEntity getMember() {
		return member;
	}

	public String getTeamPath() {
		return teamPath;
	}

	public String getTeamName() {
		return teamName;
	}

}
