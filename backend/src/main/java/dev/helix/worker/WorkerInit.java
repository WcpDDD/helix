package dev.helix.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class WorkerInit {

	private static final String STAMP = "init-version";

	private WorkerInit() {
	}

	static void ensure(Path workerRoot, Path repo) {
		if (workerRoot == null || repo == null) {
			return;
		}
		Path directory = repo.toAbsolutePath().normalize().resolve("worker-init");
		Path script = directory.resolve("init.sh");
		Path versionFile = directory.resolve("VERSION");
		if (!Files.isRegularFile(script) || !Files.isRegularFile(versionFile)) {
			return;
		}
		String version = readVersion(versionFile);
		Path stamp = workerRoot.toAbsolutePath().normalize().resolve(STAMP);
		if (version.equals(readStamp(stamp))) {
			return;
		}
		run(script, directory);
		try {
			Files.createDirectories(stamp.getParent());
			Files.writeString(stamp, version + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new IllegalStateException("初始化版本没有记下。");
		}
	}

	private static String readVersion(Path file) {
		String version;
		try {
			version = Files.readString(file, StandardCharsets.UTF_8).strip();
		}
		catch (IOException exception) {
			throw new IllegalStateException("初始化版本号没有读到。");
		}
		if (!version.matches("[A-Za-z0-9._-]{1,40}")) {
			throw new IllegalStateException("初始化版本号不对。");
		}
		return version;
	}

	private static String readStamp(Path stamp) {
		if (!Files.isRegularFile(stamp)) {
			return "";
		}
		try {
			return Files.readString(stamp, StandardCharsets.UTF_8).strip();
		}
		catch (IOException exception) {
			return "";
		}
	}

	private static void run(Path script, Path directory) {
		ProcessBuilder builder = new ProcessBuilder("sh", script.getFileName().toString());
		builder.directory(directory.toFile());
		builder.redirectErrorStream(true);
		try {
			Process process = builder.start();
			byte[] output = process.getInputStream().readAllBytes();
			if (!process.waitFor(15, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IllegalStateException("环境初始化超时了。");
			}
			String text = new String(output, StandardCharsets.UTF_8).strip();
			if (!text.isBlank()) {
				System.out.println(text);
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException("环境初始化没有完成。");
			}
		}
		catch (IllegalStateException exception) {
			throw exception;
		}
		catch (IOException exception) {
			throw new IllegalStateException("初始化脚本没有跑起来。");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("环境初始化被打断了。");
		}
	}

}
