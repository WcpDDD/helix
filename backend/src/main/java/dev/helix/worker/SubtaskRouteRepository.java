package dev.helix.worker;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubtaskRouteRepository extends JpaRepository<SubtaskRouteEntity, Long> {

	List<SubtaskRouteEntity> findByWorkerIdAndRouteStateAndWorkspaceIsNull(String workerId, String routeState);

}
