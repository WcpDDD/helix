package dev.helix.gitlab;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GitlabIssueClient {

	private final RestClient http;

	public GitlabIssueClient(RestClient http) {
		this.http = http;
	}

	public String description(String accessToken, String gitHost, String owner, String repo, int iid) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		try {
			Payload payload = http.get()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/issues/" + iid))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(Payload.class);
			return payload == null || payload.description() == null ? "" : payload.description();
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public void writeDescription(String accessToken, String gitHost, String owner, String repo, int iid,
			String description, boolean close) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		Map<String, String> body = new LinkedHashMap<>();
		body.put("description", description);
		if (close) {
			body.put("state_event", "close");
		}
		try {
			http.put()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/issues/" + iid))
					.header("Authorization", "Bearer " + accessToken)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public Map<Integer, String> descriptions(String accessToken, String gitHost, String owner, String repo,
			List<Integer> iids) {
		Map<Integer, String> found = new LinkedHashMap<>();
		if (iids.isEmpty()) {
			return found;
		}
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		try {
			for (int offset = 0; offset < iids.size(); offset += 50) {
				List<Integer> chunk = iids.subList(offset, Math.min(offset + 50, iids.size()));
				StringBuilder query = new StringBuilder();
				for (Integer iid : chunk) {
					if (!query.isEmpty()) {
						query.append('&');
					}
					query.append("iids[]=").append(iid);
				}
				IssuePayload[] payload = http.get()
						.uri(URI.create(origin + "/api/v4/projects/" + project + "/issues?" + query + "&per_page=100"))
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.body(IssuePayload[].class);
				if (payload != null) {
					for (IssuePayload issue : payload) {
						found.put(issue.iid(), issue.description() == null ? "" : issue.description());
					}
				}
			}
			return found;
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public List<GitlabComment> comments(String accessToken, String gitHost, String owner, String repo, int iid) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		List<GitlabComment> comments = new ArrayList<>();
		int page = 1;
		try {
			for (int guard = 0; guard < 20; guard++) {
				ResponseEntity<NotePayload[]> response = http.get()
						.uri(URI.create(origin + "/api/v4/projects/" + project + "/issues/" + iid
								+ "/notes?per_page=100&sort=asc&order_by=created_at&page=" + page))
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.toEntity(NotePayload[].class);
				NotePayload[] batch = response.getBody();
				if (batch != null) {
					for (NotePayload note : batch) {
						if (note.system() || note.body() == null || note.body().isBlank()) {
							continue;
						}
						comments.add(new GitlabComment(note.id(), author(note.author()), note.createdAt(), note.body()));
					}
				}
				String next = response.getHeaders().getFirst("X-Next-Page");
				if (next == null || next.isBlank()) {
					break;
				}
				page = Integer.parseInt(next);
			}
			return comments;
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

	private static String author(Author author) {
		if (author == null) {
			return "";
		}
		if (author.name() != null && !author.name().isBlank()) {
			return author.name();
		}
		return author.username() == null ? "" : author.username();
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
	private record Payload(@JsonProperty("description") String description) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record IssuePayload(int iid, String description) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NotePayload(long id, String body, boolean system, @JsonProperty("created_at") String createdAt,
			Author author) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Author(String name, String username) {
	}

}
