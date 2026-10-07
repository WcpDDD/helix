package dev.helix.auth;

import java.io.Serializable;

public record GitlabUser(long id, String username, String name, String avatarUrl) implements Serializable {
}
