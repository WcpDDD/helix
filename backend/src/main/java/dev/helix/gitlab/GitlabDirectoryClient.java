package dev.helix.gitlab;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GitlabDirectoryClient {

	private final RestClient http;

	public GitlabDirectoryClient(RestClient http) {
		this.http = http;
	}

	public GitlabDirectory load(String accessToken, String gitHost, String owner, String repo) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		List<UserPayload> users = pages(accessToken,
				origin + "/api/v4/projects/" + project + "/members/all",
				new ParameterizedTypeReference<List<UserPayload>>() {
				});
		Map<String, UserPayload> people = new LinkedHashMap<>();
		for (UserPayload user : users) {
			if (user.username() != null && !user.username().isBlank()) {
				people.putIfAbsent(user.username(), user);
			}
		}
		List<GroupPayload> groups = pages(accessToken, origin + "/api/v4/groups?top_level_only=true&all_available=true",
				new ParameterizedTypeReference<List<GroupPayload>>() {
				});
		Map<String, List<String>> teamsByLogin = new LinkedHashMap<>();
		List<GitlabDirectory.Team> teams = new ArrayList<>();
		for (GroupPayload group : groups) {
			if (group.fullPath() == null || group.fullPath().isBlank()) {
				continue;
			}
			List<UserPayload> groupUsers = groupMembers(accessToken, origin, group.id());
			Set<String> members = new LinkedHashSet<>();
			for (UserPayload user : groupUsers) {
				if (user.username() != null && people.containsKey(user.username())) {
					members.add(user.username());
				}
			}
			if (members.isEmpty()) {
				continue;
			}
			String name = group.name() == null || group.name().isBlank() ? group.fullPath() : group.name();
			teams.add(new GitlabDirectory.Team(group.fullPath(), name));
			for (String login : members) {
				teamsByLogin.computeIfAbsent(login, ignored -> new ArrayList<>()).add(group.fullPath());
			}
		}
		List<GitlabDirectory.Person> directory = new ArrayList<>();
		for (UserPayload user : people.values()) {
			String name = user.name() == null || user.name().isBlank() ? user.username() : user.name();
			directory.add(new GitlabDirectory.Person(user.username(), name,
					List.copyOf(teamsByLogin.getOrDefault(user.username(), List.of()))));
		}
		return new GitlabDirectory(directory, teams);
	}

	private List<UserPayload> groupMembers(String accessToken, String origin, long groupId) {
		try {
			return pages(accessToken, origin + "/api/v4/groups/" + groupId + "/members/all",
					new ParameterizedTypeReference<List<UserPayload>>() {
					});
		}
		catch (GitlabIssueException exception) {
			if (exception.status() == 403) {
				return List.of();
			}
			throw exception;
		}
	}

	private <T> List<T> pages(String accessToken, String url, ParameterizedTypeReference<List<T>> type) {
		List<T> all = new ArrayList<>();
		String joiner = url.contains("?") ? "&" : "?";
		for (int page = 1; page <= 20; page++) {
			List<T> chunk = get(accessToken, url + joiner + "per_page=100&page=" + page, type);
			all.addAll(chunk);
			if (chunk.size() < 100) {
				break;
			}
		}
		return all;
	}

	private <T> List<T> get(String accessToken, String url, ParameterizedTypeReference<List<T>> type) {
		try {
			List<T> body = http.get()
					.uri(URI.create(url))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(type);
			return body == null ? List.of() : body;
		}
		catch (RestClientResponseException exception) {
			throw new GitlabIssueException(exception.getStatusCode().value());
		}
		catch (RestClientException exception) {
			throw new GitlabIssueException(502);
		}
	}

	private static String origin(String gitHost) {
		String host = gitHost == null ? "" : gitHost.trim();
		while (host.endsWith("/")) {
			host = host.substring(0, host.length() - 1);
		}
		if (host.startsWith("http://") || host.startsWith("https://")) {
			return host;
		}
		return "https://" + host;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record UserPayload(String username, String name) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record GroupPayload(long id, @JsonProperty("full_path") String fullPath, String name) {
	}

}
