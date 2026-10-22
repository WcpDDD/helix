package dev.helix.board;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

	List<AgentEntity> findByProject_IdOrderByNameAsc(Long projectId);

	@Query("select distinct agent from AgentEntity agent left join fetch agent.skills where agent.project.id = :projectId")
	List<AgentEntity> findWithSkills(@Param("projectId") Long projectId);

	Optional<AgentEntity> findByIdAndProject_Id(Long id, Long projectId);

	Optional<AgentEntity> findByProject_IdAndTag(Long projectId, String tag);

	@Query("select distinct agent from AgentEntity agent left join fetch agent.skills where agent.id = :id and agent.project.id = :projectId")
	Optional<AgentEntity> findWithSkillsById(@Param("id") Long id, @Param("projectId") Long projectId);

	@Query("select distinct agent from AgentEntity agent left join fetch agent.skills where agent.project.id = :projectId and agent.tag = :tag")
	Optional<AgentEntity> findWithSkillsByTag(@Param("projectId") Long projectId, @Param("tag") String tag);

	boolean existsByProject_IdAndName(Long projectId, String name);

	boolean existsByProject_IdAndTag(Long projectId, String tag);

	boolean existsByProject_IdAndTagAndIdNot(Long projectId, String tag, Long id);

}
