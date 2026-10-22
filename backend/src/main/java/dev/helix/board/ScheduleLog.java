package dev.helix.board;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
public class ScheduleLog {

	public static final long MAX_BYTES = 100L * 1024 * 1024;

	static final String CURRENT = "schedule.jsonl";

	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private static final Pattern NAME = Pattern.compile("schedule(?:-\\d{8}-\\d{6}(?:-\\d+)?)?\\.jsonl");

	private static final TypeReference<LinkedHashMap<String, Object>> ROW = new TypeReference<>() {
	};

	private static final ObjectMapper JSON = new JsonMapper();

	private static final Logger log = LoggerFactory.getLogger(ScheduleLog.class);

	private final Path directory;

	private final long maxBytes;

	public ScheduleLog() {
		this(Path.of(System.getProperty("user.home"), "helix-logs", "schedule"), MAX_BYTES);
	}

	ScheduleLog(Path directory, long maxBytes) {
		this.directory = directory;
		this.maxBytes = Math.max(1, maxBytes);
	}

	public void write(String event, Map<String, Object> fields) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("at", ZonedDateTime.now(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		row.put("event", event);
		if (fields != null) {
			row.putAll(fields);
		}
		append(JSON.writeValueAsString(row));
	}

	public List<ScheduleFile> files() {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (Stream<Path> listing = Files.list(directory)) {
			List<ScheduleFile> files = new ArrayList<>();
			listing.filter(path -> NAME.matcher(path.getFileName().toString()).matches())
					.sorted(Comparator.comparing((Path path) -> !CURRENT.equals(path.getFileName().toString()))
							.thenComparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
					.forEach(path -> files.add(new ScheduleFile(path.getFileName().toString(), size(path),
							CURRENT.equals(path.getFileName().toString()))));
			return List.copyOf(files);
		}
		catch (IOException exception) {
			return List.of();
		}
	}

	public ScheduleView read(String name, int limit) {
		Path file = resolve(name);
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		int cap = Math.max(1, Math.min(limit, 500));
		List<String> lines = tail(file, cap);
		List<Map<String, Object>> records = new ArrayList<>();
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			try {
				records.add(JSON.readValue(line, ROW));
			}
			catch (RuntimeException exception) {
				records.add(Map.of("event", "raw", "text", line));
			}
		}
		return new ScheduleView(file.getFileName().toString(), size(file), List.copyOf(records));
	}

	private void append(String json) {
		byte[] line = (json + "\n").getBytes(StandardCharsets.UTF_8);
		synchronized (this) {
			try {
				Files.createDirectories(directory);
				Path current = directory.resolve(CURRENT);
				if (Files.isRegularFile(current) && Files.size(current) >= maxBytes) {
					roll(current);
				}
				Files.write(current, line, java.nio.file.StandardOpenOption.CREATE,
						java.nio.file.StandardOpenOption.APPEND);
			}
			catch (IOException exception) {
				log.warn("调度日志没有写上");
			}
		}
	}

	private void roll(Path current) throws IOException {
		String stamp = ZonedDateTime.now(ZONE).format(STAMP);
		Path target = directory.resolve("schedule-" + stamp + ".jsonl");
		int suffix = 2;
		while (Files.exists(target)) {
			target = directory.resolve("schedule-" + stamp + "-" + suffix + ".jsonl");
			suffix++;
		}
		Files.move(current, target);
	}

	private Path resolve(String name) {
		if (name == null || !NAME.matcher(name).matches()) {
			return null;
		}
		Path file = directory.resolve(name).normalize();
		if (!file.startsWith(directory.normalize())) {
			return null;
		}
		return file;
	}

	private List<String> tail(Path file, int limit) {
		try {
			long size = Files.size(file);
			int budget = (int) Math.min(size, 512L * 1024);
			byte[] bytes = new byte[budget];
			try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "r")) {
				access.seek(size - budget);
				access.readFully(bytes);
			}
			String text = new String(bytes, StandardCharsets.UTF_8);
			if (size > budget) {
				int newline = text.indexOf('\n');
				text = newline >= 0 ? text.substring(newline + 1) : "";
			}
			List<String> lines = new ArrayList<>();
			for (String part : text.split("\n", -1)) {
				if (!part.isBlank()) {
					lines.add(part);
				}
			}
			int from = Math.max(0, lines.size() - limit);
			return lines.subList(from, lines.size());
		}
		catch (IOException exception) {
			return List.of();
		}
	}

	private static long size(Path path) {
		try {
			return Files.size(path);
		}
		catch (IOException exception) {
			return 0;
		}
	}

	public record ScheduleFile(String name, long bytes, boolean current) {
	}

	public record ScheduleView(String name, long bytes, List<Map<String, Object>> records) {
	}

}
