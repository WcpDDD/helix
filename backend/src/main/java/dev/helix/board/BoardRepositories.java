package dev.helix.board;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AgentRepository extends JpaRepository<AgentEntity, Long> {

	List<AgentEntity> findByProject_IdOrderByNameAsc(Long projectId);

	Optional<AgentEntity> findByIdAndProject_Id(Long id, Long projectId);

	boolean existsByProject_IdAndName(Long projectId, String name);

}

interface MemberLabelRepository extends JpaRepository<MemberLabelEntity, Long> {

	List<MemberLabelEntity> findByProject_IdOrderByIdAsc(Long projectId);

	Optional<MemberLabelEntity> findByName(String name);

}

interface MemberRepository extends JpaRepository<MemberEntity, Long> {

	Optional<MemberEntity> findByGitLogin(String gitLogin);

	Optional<MemberEntity> findByProject_IdAndGitLogin(Long projectId, String gitLogin);

	@Query("select distinct member from MemberEntity member left join fetch member.roles")
	List<MemberEntity> findAllWithRoles();

	@Query("""
			select distinct member from MemberEntity member
			left join fetch member.roles
			where member.project.id = :projectId
			""")
	List<MemberEntity> findForProject(Long projectId);

}

interface MemberTeamRepository extends JpaRepository<MemberTeamEntity, Long> {

	@Query("""
			select team from MemberTeamEntity team
			join fetch team.member
			where team.member.project.id = :projectId
			""")
	List<MemberTeamEntity> findForProject(Long projectId);

	@Modifying(flushAutomatically = true)
	@Query("delete from MemberTeamEntity team where team.member.project.id = :projectId")
	void deleteForProject(Long projectId);

}

interface TaskRepository extends JpaRepository<TaskEntity, Long> {

	List<TaskEntity> findByProject_IdOrderByIdAsc(Long projectId);

}

interface IssueRepository extends JpaRepository<IssueEntity, Long> {

	long countByProject_Id(Long projectId);

	@Query("""
			select issue from IssueEntity issue
			join fetch issue.task
			join fetch issue.author
			join fetch issue.owner
			where issue.task.id in :taskIds
			order by issue.gitIssueNumber
			""")
	List<IssueEntity> findForTasks(Collection<Long> taskIds);

	@Query("""
			select issue from IssueEntity issue
			join fetch issue.project
			where issue.gitIssueNumber = :number
			""")
	Optional<IssueEntity> findByGitIssueNumber(int number);

	@Query("""
			select issue from IssueEntity issue
			join fetch issue.project
			where issue.project.id = :projectId and issue.gitIssueNumber = :number
			""")
	Optional<IssueEntity> findInProject(Long projectId, int number);

	@Query("""
			select issue from IssueEntity issue
			join fetch issue.task
			join fetch issue.author
			join fetch issue.owner
			join fetch issue.project
			where issue.project.id = :projectId
			order by issue.gitIssueNumber
			""")
	List<IssueEntity> findForProject(Long projectId);

}

interface IssueMergeRequestRepository extends JpaRepository<IssueMergeRequestEntity, Long> {

	List<IssueMergeRequestEntity> findByIssue_IdInOrderByIidAsc(Collection<Long> issueIds);

}

interface IssueEnvironmentRepository extends JpaRepository<IssueEnvironmentEntity, Long> {

	List<IssueEnvironmentEntity> findByIssue_IdInOrderByNameAsc(Collection<Long> issueIds);

}

interface IssueEdgeRepository extends JpaRepository<IssueEdgeEntity, Long> {

	@Query("""
			select edge from IssueEdgeEntity edge
			join fetch edge.fromIssue
			join fetch edge.toIssue
			where edge.task.id in :taskIds
			order by edge.fromIssue.gitIssueNumber, edge.toIssue.gitIssueNumber
			""")
	List<IssueEdgeEntity> findForTasks(Collection<Long> taskIds);

}
