package dev.helix.worker;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, Long> {

	Optional<AgentSessionEntity> findByProjectIdAndIssueNumber(long projectId, int issueNumber);

	Optional<AgentSessionEntity> findByProjectIdAndIssueNumberAndAgentId(long projectId, int issueNumber, long agentId);

	java.util.List<AgentSessionEntity> findByProjectIdAndIssueNumberOrderByAgentIdAsc(long projectId, int issueNumber);

}
