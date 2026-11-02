package dev.helix.worker;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.helix.agentkit.AgentKitRuntime.RunSink;

import org.springframework.web.server.ResponseStatusException;

public final class WorkerSessions {

	private static final Pattern ISSUE_NUMBER = Pattern.compile("\"issueNumber\"\\s*:\\s*(\\d+)");

	private static final Pattern AGENT_NAME = Pattern.compile("\"agent\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

	private final ConversationBoard board;

	private final WorkerSpec specs;

	public WorkerSessions(Path root, Map<String, String> environment, SessionStarter starter) {
		this.board = new ConversationBoard(root, environment, starter::start);
		this.specs = new WorkerSpec(root);
	}

	public List<Run> running() {
		return board.running();
	}

	public Result accept(String body) {
		int number;
		try {
			number = issueNumber(body);
		}
		catch (IllegalArgumentException exception) {
			return new Result(400, detail("issue 编号不对。"));
		}
		String agentId = agentName(body);
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
		return send(issueNumber, "", body);
	}

	public Result send(int issueNumber, String agentId, String body) {
		try {
			return board.send(issueNumber, agentId, body);
		}
		catch (RuntimeException exception) {
			String message = exception.getMessage() == null ? "消息没有送出去。" : exception.getMessage();
			return new Result(503, detail(message));
		}
	}

	public Result list(int issueNumber, Long before) {
		return list(issueNumber, "", before);
	}

	public Result list(int issueNumber, String agentId, Long before) {
		try {
			return board.list(issueNumber, agentId, before);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("对话没有读到。"));
		}
	}

	public Result content(int issueNumber, long seq) {
		return content(issueNumber, "", seq);
	}

	public Result content(int issueNumber, String agentId, long seq) {
		try {
			return board.content(issueNumber, agentId, seq);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("对话没有读到。"));
		}
	}

	public Result spec(int issueNumber) {
		try {
			return specs.read(issueNumber);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("规格没有读到。"));
		}
	}

	public Result file(int issueNumber, String path) {
		return file(issueNumber, "", path);
	}

	public Result file(int issueNumber, String agentId, String path) {
		try {
			return board.file(issueNumber, agentId, path);
		}
		catch (RuntimeException exception) {
			return new Result(503, detail("附件没有打开。"));
		}
	}

	public void stream(int issueNumber, long seq, OutputStream out) throws IOException {
		stream(issueNumber, "", seq, out);
	}

	public void stream(int issueNumber, String agentId, long seq, OutputStream out) throws IOException {
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

	static String agentName(String body) {
		if (body == null) {
			return "";
		}
		Matcher matcher = AGENT_NAME.matcher(body);
		if (!matcher.find()) {
			return "";
		}
		return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
	}

	public record Result(int status, String body) {
	}

	public record Run(int issue, String agentId) {
	}

	@FunctionalInterface
	public interface SessionStarter {
		void start(String threadId, String message, Path workspace, Map<String, String> gitlabEnv, RunSink sink,
				AgentProfile profile);
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
