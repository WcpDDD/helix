package dev.helix.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class IssueWorkspace {

	private IssueWorkspace() {
	}

	public static synchronized Path open(Path root, int issueNumber, Map<String, String> environment) {
		if (issueNumber <= 0) {
			throw new WorkspaceException("issue 编号不对。");
		}
		Path base = root.toAbsolutePath().normalize();
		Path space = base.resolve("issue-" + issueNumber).normalize();
		if (!space.startsWith(base)) {
			throw new WorkspaceException("issue 工作区不能离开 worker 目录。");
		}
		if (Files.exists(space.resolve(".git"))) {
			writeGuide(space, issueNumber, environment);
			linkSkills(space);
			return space;
		}
		if (Files.exists(space)) {
			throw new WorkspaceException("issue 工作区已经被占了。");
		}
		Path source = source(base, environment);
		if (space.startsWith(source)) {
			throw new WorkspaceException("issue 工作区不能放在代码仓库里面。");
		}
		try {
			Files.createDirectories(base);
		}
		catch (IOException exception) {
			throw new WorkspaceException("worker 目录没有建起来。");
		}
		String branch = "helix/issue-" + issueNumber;
		if (sparse(source)) {
			String patterns = gitText(source, "sparse-checkout", "list").strip();
			git(source, null, "worktree", "add", "--no-checkout", "-b", branch, space.toString(), "HEAD");
			List<String> set = new ArrayList<>();
			set.add("sparse-checkout");
			set.add("set");
			set.add("--cone");
			for (String line : patterns.split("\n")) {
				if (!line.isBlank()) {
					set.add(line.strip());
				}
			}
			if (set.size() > 3) {
				git(space, null, set.toArray(String[]::new));
			}
			git(space, null, "checkout");
		}
		else {
			git(source, null, "worktree", "add", "-b", branch, space.toString(), "HEAD");
		}
		writeGuide(space, issueNumber, environment);
		linkSkills(space);
		return space;
	}

	public static final String NO_SKILL = "helix-none";

	public static List<String> enabledSkills(Path workspace, List<String> requested) {
		List<String> names = new ArrayList<>();
		if (requested != null) {
			for (String path : requested) {
				String name = skillName(workspace, path);
				if (!name.isBlank() && !names.contains(name)) {
					names.add(name);
				}
			}
		}
		return names.isEmpty() ? List.of(NO_SKILL) : List.copyOf(names);
	}

	private static void linkSkills(Path workspace) {
		Path skills = workspace.resolve("skills");
		if (!Files.isDirectory(skills)) {
			return;
		}
		Path mount = workspace.resolve(".agents").resolve("skills");
		try (Stream<Path> walk = Files.walk(skills)) {
			List<Path> files = walk.filter(path -> path.getFileName() != null && "SKILL.md".equals(path.getFileName().toString())
					&& Files.isRegularFile(path)).toList();
			if (files.isEmpty()) {
				return;
			}
			Files.createDirectories(mount);
			for (Path file : files) {
				Path dir = file.getParent();
				if (dir == null || dir.equals(skills)) {
					continue;
				}
				String name = dir.getFileName().toString();
				if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
					continue;
				}
				Path link = mount.resolve(name);
				if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				try {
					Files.createSymbolicLink(link, mount.relativize(dir.toAbsolutePath().normalize()));
				}
				catch (IOException exception) {
					// 这个技能没挂上，继续挂其余的。
				}
			}
		}
		catch (IOException exception) {
			// 技能目录没挂上。这一轮仍按启用名单走，名单为空时不启用任何技能。
		}
	}

	private static String skillName(Path workspace, String requested) {
		if (workspace == null || requested == null || requested.isBlank() || requested.contains("..")
				|| requested.indexOf('\\') >= 0 || requested.startsWith("/")) {
			return "";
		}
		String relative = requested.strip();
		if (!relative.endsWith("SKILL.md")) {
			return "";
		}
		Path file = workspace.resolve(relative).normalize();
		if (!file.startsWith(workspace.toAbsolutePath().normalize()) || !Files.isRegularFile(file)) {
			return "";
		}
		try {
			return frontmatterName(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException exception) {
			return "";
		}
	}

	private static String frontmatterName(String markdown) {
		if (markdown == null) {
			return "";
		}
		String text = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
		if (!text.startsWith("---")) {
			return "";
		}
		int end = text.indexOf("\n---", 3);
		if (end < 0) {
			return "";
		}
		for (String line : text.substring(3, end).split("\n")) {
			String stripped = line.strip();
			if (!stripped.startsWith("name:")) {
				continue;
			}
			String value = stripped.substring("name:".length()).strip();
			if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
					|| (value.startsWith("'") && value.endsWith("'")))) {
				value = value.substring(1, value.length() - 1).strip();
			}
			else {
				int comment = value.indexOf(" #");
				if (comment >= 0) {
					value = value.substring(0, comment).strip();
				}
			}
			return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 ? "" : value;
		}
		return "";
	}

	private static void writeGuide(Path space, int issueNumber, Map<String, String> environment) {
		String markdown = WorkerGuide.markdown(issueNumber, value(environment, "HELIX_SERVER"), value(environment, "HELIX_WORKER_ID"));
		try {
			Files.writeString(space.resolve("agents.md"), markdown, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new WorkspaceException("调度说明没有写进工作区。");
		}
		String relative = gitText(space, "rev-parse", "--git-path", "info/exclude").strip();
		if (relative.isBlank()) {
			return;
		}
		Path exclude = relative.startsWith("/") ? Path.of(relative) : space.resolve(relative);
		try {
			Files.createDirectories(exclude.getParent());
			String current = Files.exists(exclude) ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
			if (current.lines().map(String::strip).noneMatch("agents.md"::equals)) {
				String next = current.isEmpty() || current.endsWith("\n") ? current + "agents.md\n" : current + "\nagents.md\n";
				Files.writeString(exclude, next, StandardCharsets.UTF_8);
			}
		}
		catch (IOException exception) {
			// 说明已经写好。进不了排除名单时，仍留在工作区里。
		}
	}

	public static Map<String, String> gitlab(Map<String, String> environment) {
		Map<String, String> copy = new LinkedHashMap<>();
		if (environment == null) {
			return Map.of();
		}
		for (Map.Entry<String, String> entry : environment.entrySet()) {
			String key = entry.getKey();
			if (key != null && key.startsWith("GITLAB_") && entry.getValue() != null) {
				copy.put(key, entry.getValue());
			}
		}
		return Map.copyOf(copy);
	}

	private static Path source(Path root, Map<String, String> environment) {
		String local = value(environment, "HELIX_REPO");
		if (local != null) {
			Path repo = Path.of(local).toAbsolutePath().normalize();
			if (!Files.exists(repo.resolve(".git"))) {
				throw new WorkspaceException("HELIX_REPO 不是一个 git 仓库。");
			}
			return repo;
		}
		Path mirror = root.resolve("mirror").normalize();
		if (Files.exists(mirror.resolve(".git"))) {
			return mirror;
		}
		String host = value(environment, "GITLAB_HOST");
		String project = value(environment, "GITLAB_PROJECT");
		String token = first(environment, "GITLAB_TOKEN", "GITLAB_PRIVATE_TOKEN");
		if (host == null || project == null || token == null) {
			throw new WorkspaceException("worker 还没有代码仓库。设置 HELIX_REPO，或设置 GITLAB_HOST、GITLAB_PROJECT 和 GITLAB_TOKEN。");
		}
		try {
			Files.createDirectories(root);
		}
		catch (IOException exception) {
			throw new WorkspaceException("worker 目录没有建起来。");
		}
		git(root, gitAuth(token), "clone", "--quiet", remote(host, project), mirror.toString());
		return mirror;
	}

	private static Map<String, String> gitAuth(String token) {
		String credential = "oauth2:" + token;
		String encoded = java.util.Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
		return Map.of(
				"GIT_CONFIG_COUNT", "1",
				"GIT_CONFIG_KEY_0", "http.extraheader",
				"GIT_CONFIG_VALUE_0", "Authorization: Basic " + encoded);
	}

	private static String remote(String host, String project) {
		String origin = host.startsWith("http://") || host.startsWith("https://") ? host : "https://" + host;
		while (origin.endsWith("/")) {
			origin = origin.substring(0, origin.length() - 1);
		}
		String path = project.startsWith("/") ? project.substring(1) : project;
		if (path.endsWith(".git")) {
			return origin + "/" + path;
		}
		return origin + "/" + path + ".git";
	}

	private static boolean sparse(Path source) {
		return "true".equals(gitText(source, "config", "--get", "core.sparseCheckout").strip());
	}

	private static String gitText(Path directory, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.add("-C");
		command.add(directory.toString());
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		try {
			Process process = builder.start();
			byte[] output = process.getInputStream().readAllBytes();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "";
			}
			if (process.exitValue() != 0) {
				return "";
			}
			return new String(output, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			return "";
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return "";
		}
	}

	private static void git(Path directory, Map<String, String> extra, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(directory.toFile());
		builder.redirectErrorStream(true);
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		if (extra != null) {
			builder.environment().putAll(extra);
		}
		try {
			Process process = builder.start();
			byte[] output = process.getInputStream().readAllBytes();
			if (!process.waitFor(3, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new WorkspaceException("代码仓库准备超时了。");
			}
			if (process.exitValue() != 0) {
				String detail = new String(output, StandardCharsets.UTF_8).strip();
				if (detail.length() > 180) {
					detail = detail.substring(0, 180);
				}
				if (detail.isBlank() || detail.contains("Authorization") || detail.toLowerCase().contains("token")) {
					detail = "代码仓库没有准备好。";
				}
				throw new WorkspaceException(detail);
			}
		}
		catch (WorkspaceException exception) {
			throw exception;
		}
		catch (IOException exception) {
			throw new WorkspaceException("git 没有跑起来。");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new WorkspaceException("代码仓库准备被打断了。");
		}
	}

	private static String first(Map<String, String> environment, String... keys) {
		for (String key : keys) {
			String value = value(environment, key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static String value(Map<String, String> environment, String key) {
		if (environment == null) {
			return null;
		}
		String value = environment.get(key);
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}

}
