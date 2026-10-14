package dev.helix.auth;

public record GitlabLogin(GitlabUser user, String accessToken, String refreshToken, long expiresAt) {
}
