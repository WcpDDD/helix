package dev.helix.agentkit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HomeSessionRepository extends JpaRepository<HomeSessionEntity, String> {

	List<HomeSessionEntity> findByProjectIdAndUserIdOrderByUpdatedAtDesc(long projectId, long userId);

	Optional<HomeSessionEntity> findFirstByProjectIdAndUserIdAndCurrentSessionTrueOrderByUpdatedAtDesc(long projectId, long userId);

	Optional<HomeSessionEntity> findByIdAndProjectIdAndUserId(String id, long projectId, long userId);

}
