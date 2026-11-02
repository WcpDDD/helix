package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

final class AgentDocument {

	private AgentDocument() {
	}

	static String render(AgentEntity agent) {
		StringBuilder text = new StringBuilder();
		text.append("---\n");
		if (agent.getTag() != null && !agent.getTag().isBlank()) {
			text.append("tag: ").append(agent.getTag().strip()).append('\n');
		}
		text.append("scope: ").append(agent.getScope()).append('\n');
		for (String role : agent.getAccess()) {
			if (role != null && !role.isBlank()) {
				text.append("access: ").append(role.strip()).append('\n');
			}
		}
		for (AgentSkillEntity skill : agent.getSkills()) {
			if (skill.getSkillPath() != null && !skill.getSkillPath().isBlank()) {
				text.append("skill: ").append(skill.getSkillPath().strip()).append('\n');
			}
		}
		text.append("---\n");
		String body = agent.getBody() == null ? "" : agent.getBody();
		if (!body.isEmpty()) {
			text.append('\n').append(body);
		}
		return text.toString();
	}

	static void readInto(AgentEntity agent, String text) {
		String normalized = text == null ? "" : text.replace("\r\n", "\n");
		if (!normalized.startsWith("---\n")) {
			agent.rewrite(normalized);
			agent.retag(null);
			agent.replaceSkills(List.of());
			return;
		}
		int close = normalized.indexOf("\n---\n", 3);
		if (close < 0) {
			agent.rewrite(normalized);
			agent.retag(null);
			agent.replaceSkills(List.of());
			return;
		}
		String header = close <= 4 ? "" : normalized.substring(4, close);
		String body = normalized.substring(close + "\n---\n".length());
		if (body.startsWith("\n")) {
			body = body.substring(1);
		}
		String tag = null;
		String scope = null;
		List<String> skills = new ArrayList<>();
		List<String> access = new ArrayList<>();
		for (String line : header.split("\n")) {
			int split = line.indexOf(':');
			if (split <= 0) {
				continue;
			}
			String key = line.substring(0, split).strip();
			String value = line.substring(split + 1).strip();
			if ("tag".equals(key)) {
				tag = value.isEmpty() ? null : value;
			}
			else if ("scope".equals(key) && !value.isEmpty()) {
				scope = value;
			}
			else if ("access".equals(key) && !value.isEmpty()) {
				access.add(value);
			}
			else if ("skill".equals(key) && !value.isEmpty()) {
				skills.add(value);
			}
		}
		agent.retag(tag);
		agent.rescope(AgentAccess.scope(scope, tag));
		agent.replaceAccess(access.isEmpty() ? AgentAccess.ROLES : AgentAccess.access(access));
		agent.replaceSkills(skills);
		agent.rewrite(body);
	}

}
