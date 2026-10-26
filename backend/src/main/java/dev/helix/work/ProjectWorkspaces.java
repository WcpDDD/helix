package dev.helix.work;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.helix.board.ProjectEntity;
import dev.helix.config.HelixProperties;
import dev.helix.gitlab.GitlabRepositoryClient;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectWorkspaces {

	private static final Logger log = LoggerFactory.getLogger(ProjectWorkspaces.class);

	private final Path root;

	private final GitlabRepositoryClient gitlab;

	private final HelixProperties properties;

	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, Push> pushes = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, String> failedTokens = new ConcurrentHashMap<>();

	private final ExecutorService publisher = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "helix-work-push");
		thread.setDaemon(true);
		return thread;
	});

	private final ExecutorService sync;

	@Autowired
	public ProjectWorkspaces(HelixProperties properties, GitlabRepositoryClient gitlab) {
		this(rootOf(properties), gitlab, properties);
	}

	public ProjectWorkspaces(Path root, GitlabRepositoryClient gitlab, HelixProperties properties) {
		this.root = root.toAbsolutePath().normalize();
		this.gitlab = gitlab;
		this.properties = properties;
		int parallel = properties == null || properties.workSyncParallel() == null || properties.workSyncParallel() < 1
				? 2
				: properties.workSyncParallel();
		this.sync = Executors.newFixedThreadPool(parallel, runnable -> {
			Thread thread = new Thread(runnable, "helix-work-sync");
			thread.setDaemon(true);
			return thread;
		});
	}

	public Path ensure(ProjectEntity project, String token) {
		Path opened = opened(project);
		if (opened != null) {
			return opened;
		}
		prepare(project, token);
		throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "主分支还在准备。");
	}

	public Path require(ProjectEntity project, String token) {
		return ensure(project, token);
	}

	public Path await(ProjectEntity project, String token) {
		Path directory = directory(project);
		if (Files.isDirectory(directory.resolve(".git"))) {
			return directory;
		}
		prepare(project, token);
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (Files.isDirectory(directory.resolve(".git"))) {
				return directory;
			}
			Push push = pushes.get(directory.toString());
			if (push != null && "failed".equals(push.phase())) {
				String message = push.error() == null || push.error().isBlank() ? "工作目录没有准备好。" : push.error();
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "工作目录准备被打断了。");
			}
		}
		throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "工作目录还没准备好。");
	}

	public void prepare(ProjectEntity project, String token) {
		Path directory = directory(project);
		if (Files.isDirectory(directory.resolve(".git"))) {
			return;
		}
		String key = directory.toString();
		synchronized (lock(key)) {
			if (Files.isDirectory(directory.resolve(".git"))) {
				return;
			}
			Push current = pushes.get(key);
			if (current != null && "preparing".equals(current.phase())) {
				return;
			}
			String branch = current == null ? "" : current.branch();
			List<WorkSnapshot.Commit> commits = current == null ? List.of() : current.commits();
			pushes.put(key, new Push("preparing", "", branch, commits));
		}
		publisher.execute(() -> cloneNow(project, token));
	}

	public Path directory(ProjectEntity project) {
		Path dir = root.resolve(segment(project.getGitHost()));
		for (String part : project.getGitOwner().split("/")) {
			dir = dir.resolve(segment(part));
		}
		dir = dir.resolve(segment(project.getGitRepo())).normalize();
		if (!dir.startsWith(root)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作目录越出了存放根。");
		}
		return dir;
	}

	public Path opened(ProjectEntity project) {
		Path directory = directory(project);
		return Files.isDirectory(directory.resolve(".git")) ? directory : null;
	}

	public WorkSnapshot status(ProjectEntity project, String token) {
		Path directory = directory(project);
		if (!Files.isDirectory(directory.resolve(".git"))) {
			Push current = pushes.get(directory.toString());
			String mark = tokenMark(token);
			boolean sameFailure = current != null && "failed".equals(current.phase())
					&& mark.equals(failedTokens.get(directory.toString()));
			if (!sameFailure) {
				prepare(project, token);
			}
			return preview(project, directory, token);
		}
		Push push = pushes.get(directory.toString());
		String phase = push == null ? "ready" : push.phase();
		if (!"pulling".equals(phase) && !"pushing".equals(phase)) {
			phase = "ready";
		}
		String error = push == null || push.error() == null ? "" : push.error();
		return new WorkSnapshot(branchOf(directory), directory.toString(), phase, error, logOf(directory),
				count(directory, "@{upstream}..HEAD"), count(directory, "HEAD..@{upstream}"), dirty(directory));
	}

	public void requestPull(ProjectEntity project, String token) {
		Path directory = opened(project);
		if (directory == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "主分支还在准备。");
		}
		claim(directory.toString(), "pulling");
		sync.execute(() -> pullNow(project, token));
	}

	public void requestPush(ProjectEntity project, String token) {
		Path directory = opened(project);
		if (directory == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "主分支还在准备。");
		}
		claim(directory.toString(), "pushing");
		sync.execute(() -> pushNow(project, token));
	}

	private void cloneNow(ProjectEntity project, String token) {
		Path directory = directory(project);
		String key = directory.toString();
		if (Files.isDirectory(directory.resolve(".git"))) {
			pushes.put(key, Push.quiet());
			return;
		}
		Push current = pushes.get(key);
		try {
			clone(remote(project), null, directory, token);
			failedTokens.remove(key);
			pushes.put(key, Push.quiet());
		}
		catch (RuntimeException exception) {
			String message = exception instanceof ResponseStatusException status && status.getReason() != null
					? status.getReason()
					: exception.getMessage();
			String readable = readableCloneFailure(message);
			log.warn("工作目录没有克隆下来: {}", message);
			String branch = current == null ? "" : current.branch();
			List<WorkSnapshot.Commit> commits = current == null ? List.of() : current.commits();
			failedTokens.put(key, tokenMark(token));
			pushes.put(key, new Push("failed", readable, branch, commits));
		}
	}

	private WorkSnapshot preview(ProjectEntity project, Path directory, String token) {
		Push current = pushes.get(directory.toString());
		String phase = current != null && "failed".equals(current.phase()) ? "failed" : "preparing";
		String error = current == null ? "" : current.error();
		String branch = current == null ? "" : current.branch();
		List<WorkSnapshot.Commit> commits = current == null ? List.of() : current.commits();
		return new WorkSnapshot(branch, directory.toString(), phase, error, commits, 0, 0, false);
	}

	Path cloneLimited(ProjectEntity project, String branch, String remote) {
		return cloneLimited(project, branch, remote, null);
	}

	Path cloneLimited(ProjectEntity project, String branch, String remote, String token) {
		Path directory = directory(project);
		clone(remote, branch, directory, token);
		return directory;
	}

	private Object lock(String key) {
		return locks.computeIfAbsent(key, ignored -> new Object());
	}

	private void claim(String key, String phase) {
		synchronized (lock(key)) {
			Push current = pushes.get(key);
			String active = current == null ? "" : current.phase();
			if ("pulling".equals(active) || "pushing".equals(active) || "preparing".equals(active)) {
				String message = "pulling".equals(active) ? "正在拉取。" : "pushing".equals(active) ? "正在推送。" : "主分支还在准备。";
				throw new ResponseStatusException(HttpStatus.CONFLICT, message);
			}
			pushes.put(key, new Push(phase, "", "", List.of()));
		}
	}

	public void pullNow(ProjectEntity project, String token) {
		Path directory = directory(project);
		String key = directory.toString();
		if (!Files.isDirectory(directory.resolve(".git"))) {
			return;
		}
		try {
			String branch = branch(directory);
			run(directory, token, "fetch", "--depth", "10", "origin", branch);
			if (count(directory, "@{upstream}..HEAD") == 0 && count(directory, "HEAD..@{upstream}") > 0) {
				run(directory, null, "merge", "--ff-only", "@{upstream}");
			}
			synchronized (lock(key)) {
				pushes.put(key, Push.quiet());
			}
		}
		catch (RuntimeException exception) {
			synchronized (lock(key)) {
				pushes.put(key, new Push("ready", reason(exception, "拉取没有完成。"), "", List.of()));
			}
		}
	}

	public void pushNow(ProjectEntity project, String token) {
		Path directory = directory(project);
		String key = directory.toString();
		if (!Files.isDirectory(directory.resolve(".git"))) {
			return;
		}
		synchronized (lock(key)) {
			pushes.put(key, new Push("pushing", "", "", List.of()));
		}
		try {
			commit(directory);
			push(directory, token);
			synchronized (lock(key)) {
				pushes.put(key, Push.quiet());
			}
		}
		catch (RuntimeException exception) {
			synchronized (lock(key)) {
				pushes.put(key, new Push("ready", reason(exception, "推送没有完成。"), "", List.of()));
			}
		}
	}

	private static String reason(RuntimeException exception, String fallback) {
		String message = exception instanceof ResponseStatusException status && status.getReason() != null
				? status.getReason()
				: exception.getMessage();
		return message == null || message.isBlank() ? fallback : message;
	}

	public String read(Path directory, String relative) {
		Path file = inside(directory, relative);
		if (!Files.isRegularFile(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		try {
			return Files.readString(file);
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件没有读到。");
		}
	}

	public List<String> markdownUnder(Path directory, String relative) {
		Path start = inside(directory, relative);
		if (Files.isRegularFile(start)) {
			return start.toString().endsWith(".md") ? List.of(relative(directory, start)) : List.of();
		}
		if (!Files.isDirectory(start)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		List<String> found = new ArrayList<>();
		try (var walk = Files.walk(start)) {
			walk.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".md"))
					.map(path -> relative(directory, path))
					.sorted()
					.forEach(found::add);
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "目录没有读到。");
		}
		return List.copyOf(found);
	}

	public String head(Path directory) {
		String sha = git(directory, "rev-parse", "HEAD").strip();
		return sha.length() <= 7 ? sha : sha.substring(0, 7);
	}

	public String branch(Path directory) {
		return git(directory, "rev-parse", "--abbrev-ref", "HEAD").strip();
	}

	@PreDestroy
	void stop() {
		publisher.shutdownNow();
		sync.shutdownNow();
	}

	private void clone(String remote, String branch, Path directory, String token) {
		Path parent = directory.getParent();
		try {
			Files.createDirectories(parent);
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工作目录没有建起来。");
		}
		Path staging = parent.resolve(directory.getFileName().toString() + ".cloning");
		delete(staging);
		try {
			List<String> args = new ArrayList<>();
			args.add("clone");
			args.add("--no-local");
			args.add("--depth");
			args.add("10");
			args.add("--filter=blob:none");
			args.add("--sparse");
			args.add("--single-branch");
			if (branch != null && !branch.isBlank()) {
				args.add("--branch");
				args.add(branch);
			}
			if (token != null && !token.isBlank()) {
				args.add("--config");
				args.add("credential.helper=" + CREDENTIAL_HELPER);
			}
			args.add(remote);
			args.add(staging.getFileName().toString());
			run(parent, token, args.toArray(String[]::new));
			run(staging, token, "sparse-checkout", "set", "skills", "openspec", "worker-init");
			if (Files.exists(directory)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "工作目录已经存在，但还不是仓库。");
			}
			Files.move(staging, directory);
		}
		catch (ResponseStatusException exception) {
			delete(staging);
			throw exception;
		}
		catch (IOException exception) {
			delete(staging);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工作目录没有就位。");
		}
	}

	private void commit(Path directory) {
		if (!dirty(directory)) {
			return;
		}
		run(directory, null, "add", "-A");
		run(directory, null, "-c", "user.name=Helix", "-c", "user.email=helix@localhost", "commit", "-m",
				"同步工作目录");
	}

	private void push(Path directory, String token) {
		String branch = branch(directory);
		run(directory, token, "push", "origin", "HEAD:" + branch);
	}

	private boolean dirty(Path directory) {
		try {
			return !git(directory, "status", "--porcelain").isBlank();
		}
		catch (RuntimeException exception) {
			return false;
		}
	}

	private int count(Path directory, String range) {
		try {
			return Integer.parseInt(git(directory, "rev-list", "--count", range).strip());
		}
		catch (RuntimeException exception) {
			return 0;
		}
	}

	private String branchOf(Path directory) {
		try {
			return branch(directory);
		}
		catch (RuntimeException exception) {
			return "";
		}
	}

	private List<WorkSnapshot.Commit> logOf(Path directory) {
		try {
			return log(directory);
		}
		catch (RuntimeException exception) {
			return List.of();
		}
	}

	private List<WorkSnapshot.Commit> log(Path directory) {
		String text = git(directory, "log", "-n", "10", "--format=%h%x1f%s%x1f%an%x1f%cI");
		if (text.isBlank()) {
			return List.of();
		}
		List<WorkSnapshot.Commit> commits = new ArrayList<>();
		for (String line : text.split("\n")) {
			String[] parts = line.split("\u001f", 4);
			if (parts.length < 4) {
				continue;
			}
			commits.add(new WorkSnapshot.Commit(parts[0], parts[1], parts[2], parts[3]));
		}
		return List.copyOf(commits);
	}

	private String remote(ProjectEntity project) {
		String origin = properties == null ? "" : properties.gitlab().origin();
		String host = hostOf(origin);
		String base = host != null && host.equalsIgnoreCase(project.getGitHost())
				? origin
				: "https://" + project.getGitHost();
		return base + "/" + project.getGitOwner() + "/" + project.getGitRepo() + ".git";
	}

	private Path inside(Path directory, String relative) {
		Path resolved = directory.resolve(relative).normalize();
		if (!resolved.startsWith(directory)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径越出了工作目录。");
		}
		return resolved;
	}

	private static String relative(Path directory, Path file) {
		return directory.relativize(file).toString().replace('\\', '/');
	}

	private String git(Path directory, String... args) {
		return run(directory, null, args);
	}

	private String run(Path directory, String token, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		if (token != null && !token.isBlank()) {
			command.add("-c");
			command.add("credential.helper=" + CREDENTIAL_HELPER);
		}
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(directory.toFile());
		builder.redirectErrorStream(true);
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		builder.environment().put("GIT_HTTP_LOW_SPEED_LIMIT", "1000");
		builder.environment().put("GIT_HTTP_LOW_SPEED_TIME", "20");
		if (token != null && !token.isBlank()) {
			builder.environment().put("HELIX_GIT_TOKEN", token);
			builder.environment().put("GIT_CONFIG_COUNT", "1");
			builder.environment().put("GIT_CONFIG_KEY_0", "http.extraheader");
			builder.environment().put("GIT_CONFIG_VALUE_0", authorizationHeader(token));
		}
		try {
			Process process = builder.start();
			java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
			Thread drain = new Thread(() -> {
				try {
					process.getInputStream().transferTo(buffer);
				}
				catch (IOException exception) {
					// 进程结束或被终止时，读取会一起停。
				}
			}, "helix-git-read");
			drain.setDaemon(true);
			drain.start();
			if (!process.waitFor(3, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "git 超时了。");
			}
			drain.join(1000);
			String output = buffer.toString(StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, brief(output));
			}
			return output;
		}
		catch (ResponseStatusException exception) {
			throw exception;
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "git 没有跑起来。");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "git 被打断了。");
		}
	}

	static final String CREDENTIAL_HELPER = "!f() { echo \"username=oauth2\"; echo \"password=$HELIX_GIT_TOKEN\"; }; f";

	static String authorizationHeader(String token) {
		String credential = "oauth2:" + token;
		String encoded = Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
		return "Authorization: Basic " + encoded;
	}

	private static String tokenMark(String token) {
		if (token == null || token.isBlank()) {
			return "";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			return "";
		}
	}

	private static String readableCloneFailure(String message) {
		String text = message == null ? "" : message;
		String lower = text.toLowerCase();
		if (lower.contains("username") || lower.contains("authentication") || lower.contains("terminal prompts")
				|| lower.contains("401") || lower.contains("403")) {
			return "这次登录不能读写仓库。退出后重新登录一次。";
		}
		return text.isBlank() ? "工作目录没有准备好。" : text;
	}

	private static String brief(String output) {
		String text = output == null ? "" : output.replaceAll("(?i)bearer\\s+\\S+", "bearer")
				.replaceAll("(?i)basic\\s+\\S+", "basic")
				.strip();
		if (text.length() > 240) {
			text = text.substring(0, 240);
		}
		return text.isBlank() ? "git 没有完成。" : text;
	}

	private static void delete(Path path) {
		if (!Files.exists(path)) {
			return;
		}
		try (var walk = Files.walk(path)) {
			walk.sorted((left, right) -> right.compareTo(left)).forEach(item -> {
				try {
					Files.deleteIfExists(item);
				}
				catch (IOException exception) {
					throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "临时目录没有清掉。");
				}
			});
		}
		catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "临时目录没有清掉。");
		}
	}

	private static String segment(String value) {
		if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value) || value.indexOf('\\') >= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库路径不能用。");
		}
		return value;
	}

	private static Path rootOf(HelixProperties properties) {
		String configured = properties.workRoot();
		if (configured == null || configured.isBlank()) {
			return Path.of(System.getProperty("user.home"), "helix-work");
		}
		return Path.of(configured);
	}

	private static String hostOf(String origin) {
		if (origin == null || origin.isBlank()) {
			return null;
		}
		try {
			return URI.create(origin).getHost();
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private record Push(String phase, String error, String branch, List<WorkSnapshot.Commit> commits) {
		static Push quiet() {
			return new Push("ready", "", "", List.of());
		}
	}

}
