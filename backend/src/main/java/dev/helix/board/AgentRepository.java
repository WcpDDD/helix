package dev.helix.board;

import java.util.List;
import java.util.Optional;

public interface AgentRepository {

	List<AgentEntity> findByProject_IdOrderByNameAsc(Long projectId);

	List<AgentEntity> findWithSkills(Long projectId);

	Optional<AgentEntity> findByProject_IdAndName(Long projectId, String name);

	Optional<AgentEntity> findByProject_IdAndTag(Long projectId, String tag);

	Optional<AgentEntity> findWithSkillsByName(Long projectId, String name);

	Optional<AgentEntity> findWithSkillsByTag(Long projectId, String tag);

	boolean existsByProject_IdAndName(Long projectId, String name);

	boolean existsByProject_IdAndTag(Long projectId, String tag);

	boolean existsByProject_IdAndTagAndNameNot(Long projectId, String tag, String name);

	List<AgentEntity> findAll();

	AgentEntity save(AgentEntity agent);

	AgentEntity save(AgentEntity agent, String previousName);

	void delete(AgentEntity agent);

}
