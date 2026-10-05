package dev.helix.project;

public record Project(
		String id,
		String name,
		String goal,
		int progress,
		String status,
		String owner
) {
}
