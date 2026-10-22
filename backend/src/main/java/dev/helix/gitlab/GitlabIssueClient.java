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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GitlabIssueClient {

	private static final Logger log = LoggerFactory.getLogger(GitlabIssueClient.class);

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

	public CreatedIssue createIssue(String accessToken, String gitHost, String owner, String repo, String title,
			String description, List<String> labels) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		List<String> names = labels == null ? List.of() : labels;
		try {
			return postIssue(accessToken, origin, project, title, description, names);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() != 400) {
				throw exception;
			}
		}
		for (String label : names) {
			ensureLabel(accessToken, origin, project, label);
		}
		return postIssue(accessToken, origin, project, title, description, names);
	}

	public UploadedFile upload(String accessToken, String gitHost, String owner, String repo, String filename,
			byte[] content) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		MultipartBodyBuilder form = new MultipartBodyBuilder();
		form.part("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		}, MediaType.APPLICATION_OCTET_STREAM);
		try {
			UploadPayload payload = http.post()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/uploads"))
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(form.build())
					.retrieve()
					.body(UploadPayload.class);
			if (payload == null || payload.markdown() == null || payload.markdown().isBlank()) {
				throw new GitlabIssueException(502);
			}
			String name = payload.alt() == null || payload.alt().isBlank() ? filename : payload.alt();
			return new UploadedFile(name, payload.markdown().strip());
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public void addLabel(String accessToken, String gitHost, String owner, String repo, int iid, String label) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		try {
			putLabel(accessToken, origin, project, iid, "add_labels", label);
		}
		catch (GitlabIssueException exception) {
			if (exception.status() != 400) {
				throw exception;
			}
			ensureLabel(accessToken, origin, project, label);
			putLabel(accessToken, origin, project, iid, "add_labels", label);
		}
	}

	public void removeLabel(String accessToken, String gitHost, String owner, String repo, int iid, String label) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		putLabel(accessToken, origin, project, iid, "remove_labels", label);
	}

	private void putLabel(String accessToken, String origin, String project, int iid, String field, String label) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put(field, label);
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

	public Map<Integer, IssueHead> heads(String accessToken, String gitHost, String owner, String repo, List<Integer> iids) {
		Map<Integer, IssueHead> found = new LinkedHashMap<>();
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
				HeadPayload[] payload = http.get()
						.uri(URI.create(origin + "/api/v4/projects/" + project + "/issues?" + query + "&per_page=100"))
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.body(HeadPayload[].class);
				if (payload != null) {
					for (HeadPayload issue : payload) {
						found.put(issue.iid(), head(issue));
					}
				}
			}
			return found;
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public List<OpenedIssue> opened(String accessToken, String gitHost, String owner, String repo) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		List<OpenedIssue> found = new ArrayList<>();
		int page = 1;
		try {
			for (int guard = 0; guard < 50; guard++) {
				ResponseEntity<OpenedPayload[]> response = http.get()
						.uri(URI.create(origin + "/api/v4/projects/" + project
								+ "/issues?state=opened&per_page=100&page=" + page))
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.toEntity(OpenedPayload[].class);
				OpenedPayload[] batch = response.getBody();
				if (batch != null) {
					for (OpenedPayload issue : batch) {
						List<String> labels = issue.labels() == null ? List.of() : List.copyOf(issue.labels());
						found.add(new OpenedIssue(issue.iid(), labels));
					}
				}
				String next = response.getHeaders().getFirst("X-Next-Page");
				if (next == null || next.isBlank() || batch == null || batch.length == 0) {
					break;
				}
				page = Integer.parseInt(next);
			}
			return found;
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	public List<IssueHead> issues(String accessToken, String gitHost, String owner, String repo) {
		return issues(accessToken, gitHost, owner, repo, 500);
	}

	List<IssueHead> issues(String accessToken, String gitHost, String owner, String repo, int limit) {
		String origin = origin(gitHost);
		String project = URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
		List<IssueHead> found = new ArrayList<>();
		int page = 1;
		int cap = Math.max(limit, 0);
		try {
			for (int guard = 0; guard < 50 && found.size() < cap; guard++) {
				ResponseEntity<HeadPayload[]> response = http.get()
						.uri(URI.create(origin + "/api/v4/projects/" + project
								+ "/issues?state=all&per_page=100&page=" + page))
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.toEntity(HeadPayload[].class);
				HeadPayload[] batch = response.getBody();
				if (batch != null) {
					for (HeadPayload issue : batch) {
						if (found.size() >= cap) {
							break;
						}
						found.add(head(issue));
					}
				}
				if (found.size() >= cap) {
					break;
				}
				String next = response.getHeaders().getFirst("X-Next-Page");
				if (next == null || next.isBlank()) {
					break;
				}
				page = Integer.parseInt(next);
			}
			return found;
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

	private CreatedIssue postIssue(String accessToken, String origin, String project, String title, String description,
			List<String> labels) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("description", description);
		body.put("labels", String.join(",", labels));
		try {
			CreatedPayload payload = http.post()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/issues"))
					.header("Authorization", "Bearer " + accessToken)
					.body(body)
					.retrieve()
					.body(CreatedPayload.class);
			if (payload == null || payload.iid() <= 0) {
				throw new GitlabIssueException(502);
			}
			return new CreatedIssue(payload.iid(), payload.webUrl() == null ? "" : payload.webUrl());
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	private void ensureLabel(String accessToken, String origin, String project, String name) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("color", "#6B4EFF");
		try {
			http.post()
					.uri(URI.create(origin + "/api/v4/projects/" + project + "/labels"))
					.header("Authorization", "Bearer " + accessToken)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		}
		catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			if (status == 400 || status == 409) {
				return;
			}
			throw failed(exception);
		}
		catch (RestClientException exception) {
			throw failed(exception);
		}
	}

	private static IssueHead head(HeadPayload issue) {
		List<String> labels = issue.labels() == null ? List.of() : List.copyOf(issue.labels());
		return new IssueHead(issue.iid(), text(issue.title()),
				issue.description() == null ? "" : issue.description(), person(issue.author()),
				person(first(issue.assignees())), text(issue.state()), labels);
	}

	private static GitlabIssueException failed(RestClientException exception) {
		if (exception instanceof RestClientResponseException response) {
			String body = response.getResponseBodyAsString();
			if (body != null && body.length() > 180) {
				body = body.substring(0, 180);
			}
			log.warn("GitLab 返回 {}: {}", response.getStatusCode().value(), body);
			return new GitlabIssueException(response.getStatusCode().value());
		}
		log.warn("GitLab 没有连上: {}", exception.toString());
		return new GitlabIssueException(502);
	}

	private static String person(Author author) {
		return author(author);
	}

	private static Author first(Author[] authors) {
		return authors == null || authors.length == 0 ? null : authors[0];
	}

	private static String text(String value) {
		return value == null ? "" : value;
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
	private record HeadPayload(int iid, String title, String description, String state, Author author,
			Author[] assignees, List<String> labels) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record OpenedPayload(int iid, List<String> labels) {
	}

	public record IssueHead(int iid, String title, String description, String author, String assignee, String state,
			List<String> labels) {
	}

	public record OpenedIssue(int iid, List<String> labels) {
	}

	public record CreatedIssue(int iid, String webUrl) {
	}

	public record UploadedFile(String name, String markdown) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record CreatedPayload(int iid, @JsonProperty("web_url") String webUrl) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record UploadPayload(String alt, String markdown) {
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
