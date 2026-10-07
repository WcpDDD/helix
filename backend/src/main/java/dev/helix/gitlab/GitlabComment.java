package dev.helix.gitlab;

public record GitlabComment(long id, String author, String createdAt, String body) {
}
