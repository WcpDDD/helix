package dev.helix.mcp;

import java.util.Map;

import dev.helix.auth.GitlabGitEnv;
import dev.helix.auth.GitlabSessionCredential;
import dev.helix.auth.LoginBootstrap;
import dev.helix.board.CurrentProject;
import dev.helix.board.ProjectEntity;
import dev.helix.board.TaskCreateService;
import dev.helix.config.HelixProperties;
import dev.helix.gitlab.GitlabIssueClient.CreatedIssue;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class McpIntake {

	private final TaskCreateService tasks;

	private final CurrentProject projects;

	private final LoginBootstrap logins;

	private final GitlabSessionCredential credential;

	private final HelixProperties properties;

	public McpIntake(TaskCreateService tasks, CurrentProject projects, LoginBootstrap logins,
			GitlabSessionCredential credential, HelixProperties properties) {
		this.tasks = tasks;
		this.projects = projects;
		this.logins = logins;
		this.credential = credential;
		this.properties = properties;
	}

	public String submit(String kind, String title, Map<String, String> fields) {
		String normalized = kind(kind);
		ProjectEntity project = projects.resolve(null);
		CreatedIssue created = tasks.create(gitlab(), project, title, normalized, fields, java.util.List.of(), true, "mcp");
		String url = created.webUrl() == null ? "" : created.webUrl();
		String lead = "feature".equals(normalized) ? "需求" : "缺陷";
		return "已创建" + lead + " #" + created.iid() + "，并交给 proposal 写方案。" + (url.isBlank() ? "" : "\n" + url);
	}

	static String kind(String raw) {
		String value = raw == null ? "" : raw.strip();
		if ("feature".equals(value) || "需求".equals(value) || "requirement".equalsIgnoreCase(value)) {
			return "feature";
		}
		if ("bug".equalsIgnoreCase(value) || "缺陷".equals(value)) {
			return "bug";
		}
		throw new IllegalArgumentException("kind 只能是 feature（需求）或 bug（缺陷）。需求要填："
				+ String.join("、", ProposalFields.feature()) + "。缺陷要填：" + String.join("、", ProposalFields.bug()) + "。");
	}

	private String gitlab() {
		String configured = GitlabGitEnv.offline(properties, null);
		if (!configured.isBlank()) {
			return configured;
		}
		logins.restore();
		String current = credential.current();
		if (current == null || current.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有可用的 GitLab 身份。");
		}
		return current;
	}

}
