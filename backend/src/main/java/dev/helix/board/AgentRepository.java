package dev.helix.board;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

	List<AgentEntity> findByProject_IdOrderByNameAsc(Long projectId);

	Optional<AgentEntity> findByIdAndProject_Id(Long id, Long projectId);

	boolean existsByProject_IdAndName(Long projectId, String name);

	boolean existsByProject_IdAndTag(Long projectId, String tag);

	boolean existsByProject_IdAndTagAndIdNot(Long projectId, String tag, Long id);

}
