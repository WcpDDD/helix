package dev.helix.worker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record AgentProfile(String body, List<String> skills) {

	private static final JsonMapper JSON = new JsonMapper();

	public AgentProfile {
		body = body == null ? "" : body;
		skills = skills == null ? List.of() : List.copyOf(skills);
	}

	public static AgentProfile read(String text) {
		if (text == null || text.isBlank()) {
			return new AgentProfile("", List.of());
		}
		try {
			return read(JSON.readTree(text));
		}
		catch (RuntimeException exception) {
			return new AgentProfile("", List.of());
		}
	}

	public static AgentProfile read(JsonNode request) {
		if (request == null || !request.isObject()) {
			return new AgentProfile("", List.of());
		}
		List<String> skills = new ArrayList<>();
		JsonNode listed = request.get("skills");
		if (listed != null && listed.isArray()) {
			for (JsonNode item : listed) {
				String path = item.asString("").strip();
				if (path.isEmpty() || path.contains("..") || path.indexOf('\\') >= 0 || path.startsWith("/")) {
					continue;
				}
				if (!skills.contains(path)) {
					skills.add(path);
				}
			}
		}
		return new AgentProfile(request.path("agentBody").asString(""), skills);
	}

	public AgentProfile resolve(Path workspace) {
		return new AgentProfile(body, IssueWorkspace.enabledSkills(workspace, skills));
	}

}
