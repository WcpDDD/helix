package dev.helix.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class BoardFile {

	private static final BoardFile EMPTY = new BoardFile(Map.of(), Set.of(), Map.of(), Map.of(), Map.of());

	private final Map<Integer, List<Integer>> childrenByMain;

	private final Set<Integer> children;

	private final Map<Integer, List<Edge>> edgesByMain;

	private final Map<Integer, String> authors;

	private final Map<Integer, List<String>> owners;

	private BoardFile(Map<Integer, List<Integer>> childrenByMain, Set<Integer> children,
			Map<Integer, List<Edge>> edgesByMain, Map<Integer, String> authors, Map<Integer, List<String>> owners) {
		this.childrenByMain = childrenByMain;
		this.children = children;
		this.edgesByMain = edgesByMain;
		this.authors = authors;
		this.owners = owners;
	}

	public static BoardFile empty() {
		return EMPTY;
	}

	public List<Integer> mains() {
		return List.copyOf(childrenByMain.keySet());
	}

	public boolean isChild(int number) {
		return children.contains(number);
	}

	public List<Integer> childrenOf(int main) {
		return childrenByMain.getOrDefault(main, List.of());
	}

	public List<Edge> edgesOf(int main) {
		return edgesByMain.getOrDefault(main, List.of());
	}

	public String authorOf(int main) {
		return authors.getOrDefault(main, "");
	}

	public List<String> ownersOf(int main) {
		return owners.getOrDefault(main, List.of());
	}

	public static BoardFile parse(String yaml) {
		if (yaml == null || yaml.isBlank()) {
			return EMPTY;
		}
		Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
		if (loaded == null) {
			return EMPTY;
		}
		Map<String, Object> document = mapping(loaded, "board.yaml 的顶层要是一组字段");
		Object version = document.get("version");
		if (version != null && number(version, "version") != 1) {
			throw new IllegalArgumentException("board.yaml 的 version 只接受 1");
		}
		Object tasks = document.get("tasks");
		if (tasks == null) {
			return EMPTY;
		}
		if (!(tasks instanceof List<?> taskList)) {
			throw new IllegalArgumentException("board.yaml 的 tasks 要是列表");
		}
		Map<Integer, List<Integer>> childrenByMain = new LinkedHashMap<>();
		Set<Integer> childNumbers = new LinkedHashSet<>();
		Set<Integer> mains = new LinkedHashSet<>();
		Map<Integer, List<Edge>> edgesByMain = new LinkedHashMap<>();
		Map<Integer, String> authors = new LinkedHashMap<>();
		Map<Integer, List<String>> owners = new LinkedHashMap<>();
		for (Object item : taskList) {
			Map<String, Object> task = mapping(item, "board.yaml 的每一条任务要是一组字段");
			int main = number(task.get("main"), "main");
			if (!mains.add(main) || childNumbers.contains(main)) {
				throw new IllegalArgumentException("issue #" + main + " 在 board.yaml 里出现了两次");
			}
			List<Integer> children = numbers(task.get("children"), main);
			for (int child : children) {
				if (child == main || mains.contains(child) || !childNumbers.add(child)) {
					throw new IllegalArgumentException("issue #" + child + " 在 board.yaml 里出现了两次");
				}
			}
			childrenByMain.put(main, List.copyOf(children));
			edgesByMain.put(main, edges(task.get("edges"), children, main));
			String author = text(task.get("author"));
			if (author != null) {
				authors.put(main, author);
			}
			List<String> ownerNames = strings(task.get("owners"), main);
			if (!ownerNames.isEmpty()) {
				owners.put(main, ownerNames);
			}
		}
		return new BoardFile(Map.copyOf(childrenByMain), Set.copyOf(childNumbers), Map.copyOf(edgesByMain),
				Map.copyOf(authors), Map.copyOf(owners));
	}

	public static String withPeople(String yaml, int main, String author, List<String> owners) {
		if (main <= 0) {
			throw new IllegalArgumentException("issue 编号不对");
		}
		if (author != null) {
			author = author.strip();
			if (author.isEmpty() || author.indexOf('\n') >= 0 || author.indexOf('\r') >= 0) {
				throw new IllegalArgumentException("创建人名字不对");
			}
		}
		if (owners != null) {
			List<String> cleaned = new ArrayList<>();
			for (String owner : owners) {
				if (owner == null) {
					continue;
				}
				String name = owner.strip();
				if (name.isEmpty() || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
					throw new IllegalArgumentException("负责人名字不对");
				}
				if (!cleaned.contains(name)) {
					cleaned.add(name);
				}
			}
			owners = List.copyOf(cleaned);
		}
		if (author == null && owners == null) {
			return yaml == null ? "" : yaml;
		}
		String text = yaml == null ? "" : yaml.replace("\r\n", "\n").replace('\r', '\n');
		if (text.isBlank()) {
			text = "version: 1\ntasks:\n";
		}
		boolean trailing = text.endsWith("\n");
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (trailing && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		if (!hasTasks(lines)) {
			throw new IllegalArgumentException("board.yaml 没有 tasks");
		}
		int start = mainLine(lines, main);
		if (start < 0) {
			lines.add("  - main: " + main);
			if (author != null) {
				lines.add("    author: " + quote(author));
			}
			if (owners != null) {
				lines.addAll(ownerLines(owners));
			}
		}
		else {
			int end = blockEnd(lines, start);
			if (author != null) {
				int at = keyLine(lines, start, end, "author");
				String rendered = "    author: " + quote(author);
				if (at >= 0) {
					lines.set(at, rendered);
				}
				else {
					lines.add(start + 1, rendered);
					end++;
				}
			}
			if (owners != null) {
				int at = keyLine(lines, start, end, "owners");
				if (at >= 0) {
					int removeUntil = at + 1;
					while (removeUntil < end) {
						String line = lines.get(removeUntil);
						if (line.isBlank() || indentOf(line) > 4) {
							removeUntil++;
							continue;
						}
						break;
					}
					lines.subList(at, removeUntil).clear();
					end -= removeUntil - at;
				}
				int authorAt = keyLine(lines, start, end, "author");
				lines.addAll(authorAt >= 0 ? authorAt + 1 : start + 1, ownerLines(owners));
			}
		}
		String next = String.join("\n", lines) + "\n";
		parse(next);
		return next;
	}

	private static boolean hasTasks(List<String> lines) {
		for (String line : lines) {
			if (line.stripTrailing().equals("tasks:")) {
				return true;
			}
		}
		return false;
	}

	private static int mainLine(List<String> lines, int number) {
		String prefix = "  - main:";
		String expected = Integer.toString(number);
		for (int index = 0; index < lines.size(); index++) {
			String trimmed = lines.get(index).stripTrailing();
			if (!trimmed.startsWith(prefix)) {
				continue;
			}
			if (trimmed.substring(prefix.length()).strip().equals(expected)) {
				return index;
			}
		}
		return -1;
	}

	private static int blockEnd(List<String> lines, int start) {
		for (int index = start + 1; index < lines.size(); index++) {
			String line = lines.get(index);
			if (line.isBlank()) {
				continue;
			}
			if (line.startsWith("  - main:") || !line.startsWith(" ")) {
				return index;
			}
		}
		return lines.size();
	}

	private static int keyLine(List<String> lines, int start, int end, String key) {
		for (int index = start + 1; index < end; index++) {
			String line = lines.get(index);
			if (indentOf(line) != 4) {
				continue;
			}
			String trimmed = line.strip();
			if (trimmed.equals(key + ":") || trimmed.startsWith(key + ":")) {
				return index;
			}
		}
		return -1;
	}

	private static int indentOf(String line) {
		int indent = 0;
		while (indent < line.length() && line.charAt(indent) == ' ') {
			indent++;
		}
		return indent;
	}

	private static List<String> ownerLines(List<String> owners) {
		if (owners.isEmpty()) {
			return List.of("    owners: []");
		}
		List<String> lines = new ArrayList<>();
		lines.add("    owners:");
		for (String owner : owners) {
			lines.add("      - " + quote(owner));
		}
		return lines;
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static List<Edge> edges(Object value, List<Integer> children, int main) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException("任务 #" + main + " 的 edges 要是列表");
		}
		Set<Integer> allowed = new LinkedHashSet<>(children);
		List<Edge> edges = new ArrayList<>();
		for (Object item : list) {
			Map<String, Object> edge = mapping(item, "任务 #" + main + " 的边要是一组字段");
			int from = number(edge.get("from"), "from");
			int to = number(edge.get("to"), "to");
			if (from == to || !allowed.contains(from) || !allowed.contains(to)) {
				throw new IllegalArgumentException("任务 #" + main + " 的边 " + from + " → " + to + " 两端都要是子 issue");
			}
			Object contract = edge.get("needs_contract");
			if (!(contract instanceof Boolean needsContract)) {
				throw new IllegalArgumentException("任务 #" + main + " 的边要写 needs_contract");
			}
			String ref = text(edge.get("contract_ref"));
			edges.add(new Edge(from, to, needsContract, ref));
		}
		return List.copyOf(edges);
	}

	private static List<Integer> numbers(Object value, int main) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException("任务 #" + main + " 的 children 要是列表");
		}
		List<Integer> numbers = new ArrayList<>();
		for (Object item : list) {
			numbers.add(number(item, "children"));
		}
		return numbers;
	}

	private static List<String> strings(Object value, int main) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException("任务 #" + main + " 的 owners 要是列表");
		}
		List<String> names = new ArrayList<>();
		for (Object item : list) {
			String name = text(item);
			if (name != null) {
				names.add(name);
			}
		}
		return List.copyOf(names);
	}

	private static int number(Object value, String label) {
		if (!(value instanceof Number number)) {
			throw new IllegalArgumentException("board.yaml 的 " + label + " 要是编号");
		}
		return number.intValue();
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static Map<String, Object> mapping(Object value, String message) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException(message);
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			fields.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return fields;
	}

	public record Edge(int from, int to, boolean needsContract, String contractRef) {
	}

}
