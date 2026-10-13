package dev.helix.board;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IssueRepository extends JpaRepository<IssueEntity, Long> {

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
