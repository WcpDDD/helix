package dev.helix.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitlabLoginRepository extends JpaRepository<GitlabLoginEntity, String> {

	Optional<GitlabLoginEntity> findByGitlabUserId(long gitlabUserId);

	Optional<GitlabLoginEntity> findFirstByOrderByUpdatedAtDesc();

}
