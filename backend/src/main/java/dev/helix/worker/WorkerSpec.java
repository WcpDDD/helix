package dev.helix.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class WorkerSpec {

	private final Path root;

	private final JsonMapper json = new JsonMapper();

	public WorkerSpec(Path root) {
		this.root = root;
	}

	public WorkerSessions.Result read(int issueNumber) {
		if (issueNumber <= 0) {
			return new WorkerSessions.Result(400, "{\"detail\":\"issue 编号不对。\"}");
		}
		Path base = root.toAbsolutePath().normalize();
		Path space = base.resolve("issue-" + issueNumber).normalize();
		if (!space.startsWith(base) || !Files.isDirectory(space.resolve(".git"))) {
			return new WorkerSessions.Result(404, "{\"detail\":\"这张 issue 还没有工作区。\"}");
		}
		String branch = git(space, "rev-parse", "--abbrev-ref", "HEAD").strip();
		String commit = shortCommit(git(space, "rev-parse", "HEAD").strip());
		ObjectNode body = json.createObjectNode();
		body.put("branch", branch);
		body.put("commit", commit);
		ArrayNode files = body.putArray("files");
		if (SpecNames.changeBranch(branch)) {
			String directory = SpecNames.directory(branch);
			Path start = space.resolve(directory).normalize();
			if (!start.startsWith(space)) {
				return new WorkerSessions.Result(400, "{\"detail\":\"规范名不能用。\"}");
			}
			for (String relative : markdownUnder(space, start)) {
				ObjectNode file = files.addObject();
				file.put("path", relative);
				file.put("markdown", read(space.resolve(relative)));
			}
		}
		return new WorkerSessions.Result(200, json.writeValueAsString(body));
	}

	private static String shortCommit(String sha) {
		return sha.length() <= 7 ? sha : sha.substring(0, 7);
	}

	private static List<String> markdownUnder(Path workspace, Path start) {
		if (!Files.isDirectory(start)) {
			return List.of();
		}
		List<String> found = new ArrayList<>();
		try (var walk = Files.walk(start)) {
			walk.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".md"))
					.map(path -> workspace.relativize(path).toString().replace('\\', '/'))
					.sorted(Comparator.naturalOrder())
					.forEach(found::add);
		}
		catch (IOException exception) {
			throw new WorkspaceException("规格没有读到。");
		}
		return List.copyOf(found);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException exception) {
			throw new WorkspaceException("规格没有读到。");
		}
	}

	private static String git(Path directory, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
		builder.redirectErrorStream(true);
		try {
			Process process = builder.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
				throw new WorkspaceException("规格没有读到。");
			}
			return output;
		}
		catch (IOException exception) {
			throw new WorkspaceException("规格没有读到。");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new WorkspaceException("规格没有读到。");
		}
	}

}
