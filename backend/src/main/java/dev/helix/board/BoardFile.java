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

	private static final BoardFile EMPTY = new BoardFile(Map.of(), Set.of(), Map.of());

	private final Map<Integer, List<Integer>> childrenByMain;

	private final Set<Integer> children;

	private final Map<Integer, List<Edge>> edgesByMain;

	private BoardFile(Map<Integer, List<Integer>> childrenByMain, Set<Integer> children,
			Map<Integer, List<Edge>> edgesByMain) {
		this.childrenByMain = childrenByMain;
		this.children = children;
		this.edgesByMain = edgesByMain;
	}

	public static BoardFile empty() {
		return EMPTY;
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
		}
		return new BoardFile(Map.copyOf(childrenByMain), Set.copyOf(childNumbers), Map.copyOf(edgesByMain));
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
