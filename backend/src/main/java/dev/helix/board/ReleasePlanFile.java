package dev.helix.board;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class ReleasePlanFile {

	private ReleasePlanFile() {
	}

	static Map<Integer, LocalDate> parse(String yaml) {
		if (yaml == null || yaml.isBlank()) {
			return new LinkedHashMap<>();
		}
		Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
		if (loaded == null) {
			return new LinkedHashMap<>();
		}
		if (!(loaded instanceof Map<?, ?> document)) {
			throw new IllegalArgumentException("release-plan.yaml 的顶层要是一组字段");
		}
		Object version = document.get("version");
		if (version != null && number(version) != 1) {
			throw new IllegalArgumentException("release-plan.yaml 的 version 只接受 1");
		}
		Object releases = document.get("releases");
		if (releases == null) {
			return new LinkedHashMap<>();
		}
		if (!(releases instanceof List<?> items)) {
			throw new IllegalArgumentException("release-plan.yaml 的 releases 要是列表");
		}
		Map<Integer, LocalDate> dates = new LinkedHashMap<>();
		for (Object item : items) {
			if (!(item instanceof Map<?, ?> row)) {
				throw new IllegalArgumentException("release-plan.yaml 的每一条要是一组字段");
			}
			int issue = number(row.get("issue"));
			if (issue <= 0) {
				throw new IllegalArgumentException("release-plan.yaml 的 issue 编号不对");
			}
			if (dates.containsKey(issue)) {
				throw new IllegalArgumentException("issue #" + issue + " 的发布日期出现了两次");
			}
			dates.put(issue, date(row.get("date"), issue));
		}
		return dates;
	}

	static String render(Map<Integer, LocalDate> dates) {
		List<Integer> issues = new ArrayList<>(dates.keySet());
		issues.sort(Integer::compareTo);
		StringBuilder text = new StringBuilder();
		text.append("version: 1\n");
		text.append("releases:\n");
		for (Integer issue : issues) {
			LocalDate date = dates.get(issue);
			if (date == null) {
				continue;
			}
			text.append("  - issue: ").append(issue).append('\n');
			text.append("    date: \"").append(date).append("\"\n");
		}
		return text.toString();
	}

	private static int number(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalArgumentException("release-plan.yaml 的编号要是数字");
	}

	private static LocalDate date(Object value, int issue) {
		if (!(value instanceof String text) || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
			throw new IllegalArgumentException("issue #" + issue + " 的发布日期不对");
		}
		try {
			return LocalDate.parse(text);
		}
		catch (DateTimeParseException exception) {
			throw new IllegalArgumentException("issue #" + issue + " 的发布日期不对");
		}
	}

}
