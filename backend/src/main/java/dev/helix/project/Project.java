package dev.helix.project;

public record Project(long id, String name, Workspace workspace) {

	public record Workspace(String host, String path) {
	}

}
