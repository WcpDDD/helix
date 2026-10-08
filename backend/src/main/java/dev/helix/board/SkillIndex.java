package dev.helix.board;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillIndex {

	private SkillIndex() {
	}

	public static List<SkillView> fromPaths(List<String> paths) {
		List<String> markdown = new ArrayList<>();
		for (String path : paths) {
			String normalized = normalize(path);
			if (normalized != null && normalized.startsWith("skills/") && normalized.endsWith(".md")
					&& !markdown.contains(normalized)) {
				markdown.add(normalized);
			}
		}
		markdown.sort(Comparator.naturalOrder());
		List<String> roots = new ArrayList<>();
		for (String path : markdown) {
			if (path.endsWith("/SKILL.md")) {
				roots.add(path.substring(0, path.length() - "/SKILL.md".length()));
			}
		}
		Map<String, List<String>> files = new LinkedHashMap<>();
		for (String root : roots) {
			files.put(root, new ArrayList<>());
		}
		for (String path : markdown) {
			String owner = null;
			for (String root : roots) {
				if (path.equals(root + "/SKILL.md") || path.startsWith(root + "/")) {
					if (owner == null || root.length() > owner.length()) {
						owner = root;
					}
				}
			}
			if (owner != null) {
				files.get(owner).add(path);
			}
		}
		List<SkillView> skills = new ArrayList<>();
		roots.sort(Comparator.naturalOrder());
		for (String root : roots) {
			List<String> group = files.get(root);
			group.sort(Comparator.comparingInt((String path) -> path.endsWith("/SKILL.md") ? 0 : 1)
					.thenComparing(Comparator.naturalOrder()));
			String name = root.startsWith("skills/") ? root.substring("skills/".length()) : root;
			skills.add(new SkillView(name, root + "/SKILL.md", List.copyOf(group)));
		}
		return List.copyOf(skills);
	}

	public static String require(String path) {
		String normalized = normalize(path);
		if (normalized == null || !normalized.startsWith("skills/") || normalized.contains("..")) {
			throw new IllegalArgumentException("只能读 skills 目录里的文件");
		}
		return normalized;
	}

	private static String normalize(String path) {
		if (path == null) {
			return null;
		}
		String text = path.strip().replace('\\', '/');
		while (text.startsWith("./")) {
			text = text.substring(2);
		}
		if (text.isEmpty() || text.contains("://")) {
			return null;
		}
		Path normalized = Path.of(text).normalize();
		if (normalized.isAbsolute() || normalized.startsWith("..")) {
			return null;
		}
		String value = normalized.toString().replace('\\', '/');
		return value.contains("..") ? null : value;
	}

	public record SkillView(String name, String path, List<String> files) {
	}

}
