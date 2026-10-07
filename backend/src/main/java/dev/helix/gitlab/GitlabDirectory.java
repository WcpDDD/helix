package dev.helix.gitlab;

import java.util.List;

public record GitlabDirectory(List<Person> people, List<Team> teams) {

	public record Person(String username, String name, List<String> teamIds) {
	}

	public record Team(String id, String name) {
	}

}
