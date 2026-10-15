package dev.helix.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
		return space;
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
