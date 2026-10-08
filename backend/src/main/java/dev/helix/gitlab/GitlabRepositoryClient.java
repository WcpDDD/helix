package dev.helix.gitlab;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GitlabRepositoryClient {

	private final RestClient http;

	public GitlabRepositoryClient(RestClient http) {
		this.http = http;
	}

	public String file(String accessToken, String gitHost, String owner, String repo, String path) {
		String branch = defaultBranch(accessToken, gitHost, owner, repo);
		return fileAt(accessToken, gitHost, owner, repo, path, branch);
	}

	public String defaultBranch(String accessToken, String gitHost, String owner, String repo) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		return defaultBranch(accessToken, origin, project);
	}

	public String commitSha(String accessToken, String gitHost, String owner, String repo, String ref) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		try {
			CommitPayload payload = http.get()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/repository/commits/" + encoded(ref)))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(CommitPayload.class);
			if (payload == null || payload.id() == null || payload.id().isBlank()) {
				throw new GitlabIssueException(404);
			}
			return payload.id();
		}
		catch (GitlabIssueException exception) {
			throw exception;
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public List<String> blobs(String accessToken, String gitHost, String owner, String repo, String path, String ref) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		List<String> paths = new ArrayList<>();
		int page = 1;
		try {
			for (int guard = 0; guard < 20; guard++) {
				String url = origin + "/api/v4/projects/" + project + "/repository/tree?path=" + encoded(path)
						+ "&ref=" + encoded(ref) + "&recursive=true&per_page=100&page=" + page;
				ResponseEntity<TreeNode[]> response = http.get()
						.uri(URI.create(url))
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.toEntity(TreeNode[].class);
				TreeNode[] batch = response.getBody();
				if (batch != null) {
					for (TreeNode node : batch) {
						if (node != null && "blob".equals(node.type()) && node.path() != null) {
							paths.add(node.path());
						}
					}
				}
				String next = response.getHeaders().getFirst("X-Next-Page");
				if (next == null || next.isBlank()) {
					return paths;
				}
				page = Integer.parseInt(next);
			}
			return paths;
		}
		catch (GitlabIssueException exception) {
			throw exception;
		}
		catch (RestClientException exception) {
			GitlabIssueException gitlabError = failed(exception);
			if (gitlabError.status() == 404) {
				return null;
			}
			throw gitlabError;
		}
	}

	public String fileAt(String accessToken, String gitHost, String owner, String repo, String path, String ref) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		try {
			String body = http.get()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/repository/files/" + encoded(path)
							+ "/raw?ref=" + encoded(ref)))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(String.class);
			return body == null ? "" : body;
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	private static String encoded(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record CommitPayload(String id) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TreeNode(String path, String type) {
	}

}
