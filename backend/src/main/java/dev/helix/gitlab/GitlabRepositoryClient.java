package dev.helix.gitlab;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GitlabRepositoryClient {

	private final RestClient http;

	public GitlabRepositoryClient(RestClient http) {
		this.http = http;
	}

	public String file(String accessToken, String gitHost, String owner, String repo, String path) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		String branch = defaultBranch(accessToken, origin, project);
		String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
		String ref = URLEncoder.encode(branch, StandardCharsets.UTF_8);
		try {
			String body = http.get()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/repository/files/" + encodedPath
							+ "/raw?ref=" + ref))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(String.class);
			return body == null ? "" : body;
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	private String defaultBranch(String accessToken, String origin, String project) {
		try {
			ProjectPayload payload = http.get()
					.uri(URI.create(origin + "/api/v4/projects/" + project))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(ProjectPayload.class);
			if (payload == null || payload.defaultBranch() == null || payload.defaultBranch().isBlank()) {
				return "main";
			}
			return payload.defaultBranch();
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	private static GitlabIssueException failed(RestClientException exception) {
		if (exception instanceof RestClientResponseException response) {
			return new GitlabIssueException(response.getStatusCode().value());
		}
		return new GitlabIssueException(502);
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
	private record ProjectPayload(@JsonProperty("default_branch") String defaultBranch) {
	}

}
