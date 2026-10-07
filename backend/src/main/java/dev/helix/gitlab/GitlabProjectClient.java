package dev.helix.gitlab;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GitlabProjectClient {

	private final RestClient http;

	public GitlabProjectClient(RestClient http) {
		this.http = http;
	}

	public List<GitlabProjectRef> membership(String accessToken, String origin, String search) {
		StringBuilder url = new StringBuilder(origin)
				.append("/api/v4/projects?membership=true&simple=true&per_page=50&order_by=last_activity_at&sort=desc");
		if (search != null && !search.isBlank()) {
			url.append("&search=").append(URLEncoder.encode(search.trim(), StandardCharsets.UTF_8));
		}
		try {
			List<Payload> body = http.get()
					.uri(URI.create(url.toString()))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(new ParameterizedTypeReference<List<Payload>>() {
					});
			if (body == null) {
				return List.of();
			}
			return body.stream().map(Payload::toRef).filter(project -> !project.path().isBlank()).toList();
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public GitlabProjectRef find(String accessToken, String origin, String path) {
		String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
		try {
			Payload body = http.get()
					.uri(URI.create(origin + "/api/v4/projects/" + encoded))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(Payload.class);
			if (body == null || body.path().isBlank()) {
				throw new GitlabIssueException(404);
			}
			return body.toRef();
		}
		catch (GitlabIssueException exception) {
			throw exception;
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

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Payload(
			@JsonProperty("path_with_namespace") String path,
			String name,
			@JsonProperty("web_url") String webUrl) {

		GitlabProjectRef toRef() {
			return new GitlabProjectRef(path == null ? "" : path, name == null ? "" : name, webUrl == null ? "" : webUrl);
		}

	}

}
