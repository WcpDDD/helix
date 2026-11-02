package dev.helix.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitlabLoginRepository extends JpaRepository<GitlabLoginEntity, String> {

	Optional<GitlabLoginEntity> findByGitlabUserId(long gitlabUserId);

	Optional<GitlabLoginEntity> findByAccessToken(String accessToken);

	Optional<GitlabLoginEntity> findFirstByOrderByUpdatedAtDesc();

	Optional<GitlabLoginEntity> findByUsername(String username);

	Optional<GitlabLoginEntity> findFirstByLdapUid(String ldapUid);

}
