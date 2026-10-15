package dev.helix.worker;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.helix.agentkit.AgentKitRuntime.RunSink;

import org.springframework.web.server.ResponseStatusException;

public final class WorkerSessions {

	private static final Pattern ISSUE_NUMBER = Pattern.compile("\"issueNumber\"\\s*:\\s*(\\d+)");

	private static final Pattern AGENT_ID = Pattern.compile("\"agentId\"\\s*:\\s*(\\d+)");

	private final ConversationBoard board;

	public WorkerSessions(Path root, Map<String, String> environment, SessionStarter starter) {
		this.board = new ConversationBoard(root, environment, starter::start);
	}

	public Result accept(String body) {
		int number;
		try {
			number = issueNumber(body);
		}
		catch (IllegalArgumentException exception) {
			return new Result(400, detail("issue 编号不对。"));
		}
		long agentId = agentId(body);
		try {
			return board.open(number, agentId);
		}
		catch (RuntimeException exception) {
			String message = exception instanceof ResponseStatusException status && status.getReason() != null
					? status.getReason()
					: exception.getMessage();
			return new Result(503, detail(message == null || message.isBlank() ? "创建 Agent 会话失败。" : message));
		}
	}

	public Result send(int issueNumber, String body) {
		return send(issueNumber, 0, body);
	}

	public Result send(int issueNumber, long agentId, String body) {
		try {
			return board.send(issueNumber, agentId, body);
		}
		catch (RuntimeException exception) {
			String message = exception.getMessage() == null ? "消息没有送出去。" : exception.getMessage();
			return new Result(503, detail(message));
		}
	}

	public Result list(int issueNumber, Long before) {
		return list(issueNumber, 0, before);
	}

	public Result list(int issueNumber, long agentId, Long before) {
		try {
			return board.list(issueNumber, agentId, before);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("对话没有读到。"));
		}
	}

	public Result content(int issueNumber, long seq) {
		return content(issueNumber, 0, seq);
	}

	public Result content(int issueNumber, long agentId, long seq) {
		try {
			return board.content(issueNumber, agentId, seq);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("对话没有读到。"));
		}
	}

	public Result file(int issueNumber, String path) {
		return file(issueNumber, 0, path);
	}

	public Result file(int issueNumber, long agentId, String path) {
		try {
			return board.file(issueNumber, agentId, path);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("附件没有打开。"));
		}
	}

	public void stream(int issueNumber, long seq, OutputStream out) throws IOException {
		stream(issueNumber, 0, seq, out);
	}

	public void stream(int issueNumber, long agentId, long seq, OutputStream out) throws IOException {
		board.writeStream(issueNumber, agentId, seq, out);
	}

	static int issueNumber(String body) {
		if (body == null) {
			throw new IllegalArgumentException();
		}
		Matcher matcher = ISSUE_NUMBER.matcher(body);
		if (!matcher.find()) {
			throw new IllegalArgumentException();
		}
		try {
			int number = Integer.parseInt(matcher.group(1));
			if (number <= 0) {
				throw new IllegalArgumentException();
			}
			return number;
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException();
		}
	}

	static long agentId(String body) {
		if (body == null) {
			return 0;
		}
		Matcher matcher = AGENT_ID.matcher(body);
		if (!matcher.find()) {
			return 0;
		}
		try {
			long agentId = Long.parseLong(matcher.group(1));
			if (agentId < 0) {
				throw new IllegalArgumentException();
			}
			return agentId;
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException();
		}
	}

	static String task(int issueNumber) {
		return """
				分析这个代码仓库，并读取当前 GitLab issue #%d 的信息。
				说明仓库里和这张 issue 相关的代码，以及 issue 的标题、描述和状态。
				GitLab 的地址、项目和凭据在环境变量里，变量名以 GITLAB_ 开头。不要把凭据写进文件或回复。
				""".formatted(issueNumber);
	}

	public record Result(int status, String body) {
	}

	@FunctionalInterface
	public interface SessionStarter {
		void start(String threadId, String message, Path workspace, Map<String, String> gitlabEnv, RunSink sink);
	}

	private static String detail(String message) {
		String text = message == null || message.isBlank() ? "对话没有读到。" : message.strip();
		String lower = text.toLowerCase();
		if (lower.contains("glpat-") || lower.contains("bearer ") || lower.contains("api_key") || text.contains("Authorization")) {
			text = "对话没有读到。";
		}
		if (text.length() > 180) {
			text = text.substring(0, 180);
		}
		return "{\"detail\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}

}
