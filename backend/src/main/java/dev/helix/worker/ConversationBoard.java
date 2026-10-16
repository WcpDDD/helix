package dev.helix.worker;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.agentkit.AgentKitRuntime.RunSink;

import io.agentkit.api.execution.ExecutionChunk;
import io.agentkit.api.execution.ExecutionEvent;
import io.agentkit.api.execution.ExecutionPhase;

public final class ConversationBoard {

	static final int PAGE = 10;

	private final Path root;

	private final Map<String, String> environment;

	private final Runner runner;

	private final JsonMapper json = new JsonMapper();

	private final Map<String, IssueTalk> talks = new ConcurrentHashMap<>();

	public ConversationBoard(Path root, Map<String, String> environment, Runner runner) {
		this.root = root;
		this.environment = environment == null ? Map.of() : environment;
		this.runner = runner;
	}

	public WorkerSessions.Result open(int issueNumber) {
		return open(issueNumber, 0);
	}

	public WorkerSessions.Result open(int issueNumber, long agentId) {
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			if (!talk.messages.isEmpty() || talk.busy) {
				return ready(issueNumber);
			}
		}
		IssueWorkspace.open(root, issueNumber, environment);
		return ready(issueNumber);
	}

	public WorkerSessions.Result send(int issueNumber, String body) {
		return send(issueNumber, 0, body);
	}

	public WorkerSessions.Result send(int issueNumber, long agentId, String body) {
		JsonNode request;
		try {
			request = body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
		}
		catch (RuntimeException exception) {
			return new WorkerSessions.Result(400, detail("消息格式不对。"));
		}
		String text = request.path("text").asString("").strip();
		if (text.isEmpty()) {
			return new WorkerSessions.Result(400, detail("要说一句让这个 agent 做的事。"));
		}
		List<String> files = new ArrayList<>();
		JsonNode listed = request.get("files");
		if (listed != null && listed.isArray()) {
			for (JsonNode item : listed) {
				String path = item.asString("").strip();
				if (!path.isEmpty()) {
					files.add(path);
				}
			}
		}
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			if (talk.busy) {
				return new WorkerSessions.Result(409, detail("上一轮还在进行。"));
			}
			talk.busy = true;
		}
		Path workspace;
		try {
			workspace = IssueWorkspace.open(root, issueNumber, environment);
			for (String file : files) {
				Path resolved = resolve(workspace, file);
				if (!Files.isRegularFile(resolved)) {
					throw new WorkspaceException("附件不在工作区里。");
				}
			}
		}
		catch (RuntimeException exception) {
			synchronized (talk.lock) {
				talk.busy = false;
			}
			String message = exception.getMessage() == null ? "消息没有送出去。" : exception.getMessage();
			int status = exception instanceof WorkspaceException ? 400 : 503;
			return new WorkerSessions.Result(status, detail(message));
		}
		long assistant = appendPair(talk, text, files);
		String prompt = text;
		if (!files.isEmpty()) {
			prompt = text + "\n\n附件：\n- " + String.join("\n- ", files);
		}
		try {
			start(talk, issueNumber, assistant, prompt, workspace, AgentProfile.read(request));
		}
		catch (RuntimeException ignored) {
			// 失败已经写进这条回复。
		}
		ObjectNode user = message(talk, assistant - 1);
		ObjectNode reply = message(talk, assistant);
		ObjectNode payload = json.createObjectNode();
		payload.set("user", copy(user));
		payload.set("assistant", copy(reply));
		return new WorkerSessions.Result(200, json.writeValueAsString(payload));
	}

	public WorkerSessions.Result list(int issueNumber, Long before) {
		return list(issueNumber, 0, before);
	}

	public WorkerSessions.Result list(int issueNumber, long agentId, Long before) {
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			List<ObjectNode> eligible = new ArrayList<>();
			for (JsonNode node : talk.messages) {
				long seq = node.path("seq").asLong();
				if (before == null || seq < before) {
					eligible.add((ObjectNode) node);
				}
			}
			boolean hasMore = eligible.size() > PAGE;
			List<ObjectNode> page = hasMore ? eligible.subList(eligible.size() - PAGE, eligible.size()) : eligible;
			ArrayNode messages = json.createArrayNode();
			for (ObjectNode message : page) {
				ObjectNode item = json.createObjectNode();
				item.put("seq", message.path("seq").asLong());
				item.put("role", message.path("role").asString(""));
				item.put("status", message.path("status").asString(""));
				item.put("createdAt", message.path("createdAt").asLong());
				item.put("preview", preview(message));
				messages.add(item);
			}
			ObjectNode payload = json.createObjectNode();
			payload.set("messages", messages);
			payload.put("hasMore", hasMore);
			return new WorkerSessions.Result(200, json.writeValueAsString(payload));
		}
	}

	public WorkerSessions.Result content(int issueNumber, long seq) {
		return content(issueNumber, 0, seq);
	}

	public WorkerSessions.Result content(int issueNumber, long agentId, long seq) {
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			ObjectNode message = message(talk, seq);
			if (message == null) {
				return new WorkerSessions.Result(404, detail("这条对话没有。"));
			}
			return new WorkerSessions.Result(200, json.writeValueAsString(copy(message)));
		}
	}

	public WorkerSessions.Result file(int issueNumber, String path) {
		return file(issueNumber, 0, path);
	}

	public WorkerSessions.Result file(int issueNumber, long agentId, String path) {
		if (path == null || path.isBlank()) {
			return new WorkerSessions.Result(400, detail("附件路径不对。"));
		}
		try {
			Path workspace = IssueWorkspace.open(root, issueNumber, environment);
			Path file = resolve(workspace, path.strip());
			if (!Files.isRegularFile(file)) {
				return new WorkerSessions.Result(404, detail("附件不在工作区里。"));
			}
			byte[] bytes = Files.readAllBytes(file);
			boolean binary = false;
			int length = Math.min(bytes.length, 256 * 1024);
			for (int i = 0; i < length; i++) {
				if (bytes[i] == 0) {
					binary = true;
					break;
				}
			}
			ObjectNode payload = json.createObjectNode();
			payload.put("path", path.strip());
			payload.put("name", file.getFileName().toString());
			payload.put("binary", binary);
			if (binary) {
				payload.put("text", "");
			}
			else {
				String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
				if (bytes.length > length) {
					text = text + "\n\n文件后面还有内容，这里只打开了前面一部分。";
				}
				payload.put("text", text);
			}
			return new WorkerSessions.Result(200, json.writeValueAsString(payload));
		}
		catch (WorkspaceException exception) {
			return new WorkerSessions.Result(400, detail(exception.getMessage()));
		}
		catch (IOException exception) {
			return new WorkerSessions.Result(404, detail("附件没有打开。"));
		}
	}

	public void writeStream(int issueNumber, long seq, OutputStream out) throws IOException {
		writeStream(issueNumber, 0, seq, out);
	}

	public void writeStream(int issueNumber, long agentId, long seq, OutputStream out) throws IOException {
		IssueTalk talk = talk(issueNumber, agentId);
		BlockingQueue<String> queue = new LinkedBlockingQueue<>();
		String snapshot;
		boolean live;
		synchronized (talk.lock) {
			ObjectNode message = message(talk, seq);
			if (message == null) {
				throw new WorkspaceException("这条对话没有。");
			}
			snapshot = frame("snapshot", copy(message));
			live = "running".equals(message.path("status").asString(""));
			if (live) {
				talk.listeners.computeIfAbsent(seq, key -> new ArrayList<>()).add(queue);
			}
		}
		try {
			write(out, snapshot);
			if (!live) {
				write(out, frame("done", statusNode("done")));
				return;
			}
			while (true) {
				String next;
				try {
					next = queue.poll(15, TimeUnit.SECONDS);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				if (next == null) {
					write(out, ": ping\n\n");
					continue;
				}
				write(out, next);
				if (next.startsWith("event: done")) {
					return;
				}
			}
		}
		finally {
			synchronized (talk.lock) {
				List<BlockingQueue<String>> queues = talk.listeners.get(seq);
				if (queues != null) {
					queues.remove(queue);
				}
			}
		}
	}

	private void start(IssueTalk talk, int issueNumber, long assistant, String prompt, Path workspace, AgentProfile profile) {
		String threadId;
		synchronized (talk.lock) {
			if (talk.threadId == null || talk.threadId.isBlank()) {
				talk.threadId = UUID.randomUUID().toString();
				save(talk);
			}
			threadId = talk.threadId;
		}
		AgentProfile turn = profile == null ? new AgentProfile("", List.of()) : profile;
		try {
			runner.start(threadId, prompt, workspace, IssueWorkspace.gitlab(environment), new TurnSink(issueNumber, talk.agentId, assistant),
					turn.resolve(workspace));
		}
		catch (RuntimeException exception) {
			finish(issueNumber, talk.agentId, assistant, "failed", safe(exception.getMessage()));
			throw exception;
		}
	}

	private long appendPair(IssueTalk talk, String text, List<String> files) {
		synchronized (talk.lock) {
			long userSeq = nextSeq(talk);
			ObjectNode user = talk.messages.addObject();
			user.put("seq", userSeq);
			user.put("role", "user");
			user.put("status", "done");
			user.put("createdAt", System.currentTimeMillis());
			ArrayNode parts = user.putArray("parts");
			ObjectNode textPart = parts.addObject();
			textPart.put("kind", "text");
			textPart.put("text", text);
			for (String file : files) {
				addFile(parts, file);
			}
			long assistant = userSeq + 1;
			ObjectNode reply = talk.messages.addObject();
			reply.put("seq", assistant);
			reply.put("role", "assistant");
			reply.put("status", "running");
			reply.put("createdAt", System.currentTimeMillis());
			reply.putArray("parts");
			save(talk);
			return assistant;
		}
	}

	private void applyChunk(int issueNumber, long agentId, long seq, ExecutionChunk chunk) {
		if (chunk == null) {
			return;
		}
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			ObjectNode message = running(talk, seq);
			if (message == null) {
				return;
			}
			if (chunk.reasoning() != null && !chunk.reasoning().isEmpty()) {
				appendText(talk, message, "thinking", chunk.reasoning());
			}
			if (chunk.text() != null && !chunk.text().isEmpty()) {
				appendText(talk, message, "text", chunk.text());
			}
			save(talk);
		}
	}

	private void applyEvent(int issueNumber, long agentId, long seq, ExecutionEvent event) {
		if (event == null) {
			return;
		}
		if (event instanceof ExecutionEvent.ToolStarted started) {
			toolStarted(issueNumber, agentId, seq, started);
			return;
		}
		if (event instanceof ExecutionEvent.ToolFinished finished) {
			toolFinished(issueNumber, agentId, seq, finished);
			return;
		}
		if (event instanceof ExecutionEvent.TurnFinished finished) {
			turnText(issueNumber, agentId, seq, finished.assistantText());
			return;
		}
		if (event instanceof ExecutionEvent.MaxTurnsReached) {
			finish(issueNumber, agentId, seq, "failed", "已经到达轮次上限。");
			return;
		}
		if (event instanceof ExecutionEvent.RunEnded ended) {
			String status = ended.phase() == ExecutionPhase.COMPLETED ? "done" : "failed";
			String detail = ended.phase() == ExecutionPhase.COMPLETED ? null : ended.failureMessage();
			if ((detail == null || detail.isBlank()) && ended.assistantText() != null && !ended.assistantText().isBlank()) {
				turnText(issueNumber, agentId, seq, ended.assistantText());
			}
			finish(issueNumber, agentId, seq, status, detail);
		}
	}

	private void toolStarted(int issueNumber, long agentId, long seq, ExecutionEvent.ToolStarted started) {
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			ObjectNode message = running(talk, seq);
			if (message == null) {
				return;
			}
			ArrayNode parts = parts(message);
			ObjectNode tool = parts.addObject();
			tool.put("kind", "tool");
			tool.put("id", started.toolCallId() == null ? "" : started.toolCallId());
			tool.put("name", started.name() == null ? "" : started.name());
			tool.put("arguments", started.arguments() == null ? "" : started.arguments());
			tool.put("status", "running");
			publish(talk, seq, "tool", tool);
			String path = toolPath(started.name(), started.arguments());
			if (path != null) {
				addFile(parts, path);
				publish(talk, seq, "file", parts.get(parts.size() - 1));
			}
			save(talk);
		}
	}

	private void toolFinished(int issueNumber, long agentId, long seq, ExecutionEvent.ToolFinished finished) {
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			ObjectNode message = running(talk, seq);
			if (message == null) {
				return;
			}
			String id = finished.toolCallId() == null ? "" : finished.toolCallId();
			for (JsonNode part : parts(message)) {
				if ("tool".equals(part.path("kind").asString("")) && id.equals(part.path("id").asString(""))) {
					ObjectNode tool = (ObjectNode) part;
					tool.put("status", "done");
					tool.put("observation", finished.observation() == null ? "" : finished.observation());
					publish(talk, seq, "tool", tool);
					break;
				}
			}
			save(talk);
		}
	}

	private void turnText(int issueNumber, long agentId, long seq, String assistantText) {
		if (assistantText == null || assistantText.isBlank()) {
			return;
		}
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			ObjectNode message = running(talk, seq);
			if (message == null) {
				return;
			}
			String current = joined(message, "text");
			if (assistantText.equals(current)) {
				return;
			}
			String extra = assistantText.startsWith(current) ? assistantText.substring(current.length()) : "";
			if (current.isEmpty()) {
				appendText(talk, message, "text", assistantText);
			}
			else if (!extra.isEmpty()) {
				appendText(talk, message, "text", extra);
			}
			save(talk);
		}
	}

	private void finish(int issueNumber, long agentId, long seq, String status, String detail) {
		IssueTalk talk = talk(issueNumber, agentId);
		synchronized (talk.lock) {
			ObjectNode message = message(talk, seq);
			if (message == null || !"running".equals(message.path("status").asString(""))) {
				talk.busy = false;
				return;
			}
			if (detail != null && !detail.isBlank() && joined(message, "text").isEmpty()) {
				appendText(talk, message, "text", safe(detail));
			}
			message.put("status", status);
			talk.busy = false;
			publish(talk, seq, "done", statusNode(status));
			save(talk);
		}
	}

	private void appendText(IssueTalk talk, ObjectNode message, String kind, String delta) {
		ArrayNode parts = parts(message);
		JsonNode last = parts.isEmpty() ? null : parts.get(parts.size() - 1);
		if (last != null && kind.equals(last.path("kind").asString(""))) {
			((ObjectNode) last).put("text", last.path("text").asString("") + delta);
		}
		else {
			ObjectNode part = parts.addObject();
			part.put("kind", kind);
			part.put("text", delta);
		}
		ObjectNode payload = json.createObjectNode();
		payload.put("text", delta);
		publish(talk, message.path("seq").asLong(), kind, payload);
	}

	private void publish(IssueTalk talk, long seq, String event, JsonNode data) {
		List<BlockingQueue<String>> queues = talk.listeners.get(seq);
		if (queues == null) {
			return;
		}
		String frame = frame(event, data);
		for (BlockingQueue<String> queue : queues) {
			queue.offer(frame);
		}
	}

	private String frame(String event, JsonNode data) {
		return "event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n";
	}

	private ObjectNode statusNode(String status) {
		ObjectNode node = json.createObjectNode();
		node.put("status", status);
		return node;
	}

	private void addFile(ArrayNode parts, String path) {
		for (JsonNode part : parts) {
			if ("file".equals(part.path("kind").asString("")) && path.equals(part.path("path").asString(""))) {
				return;
			}
		}
		String name = path;
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		if (slash >= 0 && slash < path.length() - 1) {
			name = path.substring(slash + 1);
		}
		ObjectNode file = parts.addObject();
		file.put("kind", "file");
		file.put("name", name);
		file.put("path", path);
	}

	private String toolPath(String name, String arguments) {
		if (!"read".equals(name) && !"write".equals(name)) {
			return null;
		}
		if (arguments == null || arguments.isBlank()) {
			return null;
		}
		try {
			String path = json.readTree(arguments).path("path").asString("").strip();
			if (path.isEmpty() || path.startsWith("/") || path.contains("..")) {
				return null;
			}
			return path;
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	private static String joined(ObjectNode message, String kind) {
		StringBuilder text = new StringBuilder();
		for (JsonNode part : parts(message)) {
			if (kind.equals(part.path("kind").asString(""))) {
				text.append(part.path("text").asString(""));
			}
		}
		return text.toString();
	}

	private static String preview(ObjectNode message) {
		String thinking = "";
		String named = "";
		for (JsonNode part : parts(message)) {
			String kind = part.path("kind").asString("");
			if ("text".equals(kind)) {
				String text = squash(part.path("text").asString(""));
				if (!text.isEmpty()) {
					return clip(text);
				}
			}
			else if ("thinking".equals(kind) && thinking.isEmpty()) {
				thinking = squash(part.path("text").asString(""));
			}
			else if (named.isEmpty() && ("file".equals(kind) || "tool".equals(kind))) {
				named = part.path("name").asString("");
			}
		}
		if (!named.isEmpty()) {
			return clip(named);
		}
		return clip(thinking);
	}

	private static String squash(String text) {
		return text.strip().replaceAll("\\s+", " ");
	}

	private static String clip(String text) {
		return text.length() <= 80 ? text : text.substring(0, 80);
	}

	private ObjectNode copy(ObjectNode message) {
		ObjectNode copy = json.createObjectNode();
		copy.put("seq", message.path("seq").asLong());
		copy.put("role", message.path("role").asString(""));
		copy.put("status", message.path("status").asString(""));
		copy.put("createdAt", message.path("createdAt").asLong());
		copy.set("parts", message.get("parts").deepCopy());
		return copy;
	}

	private static ArrayNode parts(ObjectNode message) {
		return (ArrayNode) message.get("parts");
	}

	private ObjectNode running(IssueTalk talk, long seq) {
		ObjectNode message = message(talk, seq);
		if (message == null || !"running".equals(message.path("status").asString(""))) {
			return null;
		}
		return message;
	}

	private static ObjectNode message(IssueTalk talk, long seq) {
		for (JsonNode node : talk.messages) {
			if (node.path("seq").asLong() == seq) {
				return (ObjectNode) node;
			}
		}
		return null;
	}

	private static long nextSeq(IssueTalk talk) {
		if (talk.messages.isEmpty()) {
			return 1;
		}
		return talk.messages.get(talk.messages.size() - 1).path("seq").asLong() + 1;
	}

	private IssueTalk talk(int issueNumber, long agentId) {
		if (issueNumber <= 0) {
			throw new WorkspaceException("issue 编号不对。");
		}
		if (agentId < 0) {
			throw new WorkspaceException("agent 不对。");
		}
		return talks.computeIfAbsent(issueNumber + ":" + agentId, key -> load(issueNumber, agentId));
	}

	private IssueTalk load(int issueNumber, long agentId) {
		IssueTalk talk = new IssueTalk(issueNumber, agentId, json.createArrayNode());
		Path file = store(talk);
		if (!Files.isRegularFile(file)) {
			return talk;
		}
		try {
			JsonNode saved = json.readTree(Files.readString(file));
			JsonNode messages = saved;
			if (saved != null && saved.isObject()) {
				String threadId = saved.path("threadId").asString("").strip();
				talk.threadId = threadId.isEmpty() ? null : threadId;
				messages = saved.get("messages");
			}
			if (messages != null && messages.isArray()) {
				boolean repair = false;
				for (JsonNode node : messages) {
					if (node instanceof ObjectNode message && message.get("parts") instanceof ArrayNode) {
						if ("running".equals(message.path("status").asString(""))) {
							message.put("status", "failed");
							ObjectNode note = ((ArrayNode) message.get("parts")).addObject();
							note.put("kind", "text");
							note.put("text", "这轮在 worker 重启时中断了。");
							repair = true;
						}
						talk.messages.add(message);
					}
				}
				if (repair) {
					save(talk);
				}
			}
		}
		catch (RuntimeException | IOException exception) {
			System.out.println("会话记录没有读出来 " + issueNumber);
		}
		return talk;
	}

	private void save(IssueTalk talk) {
		Path file = store(talk);
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		ObjectNode body = json.createObjectNode();
		body.put("threadId", talk.threadId == null ? "" : talk.threadId);
		body.set("messages", talk.messages);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(temporary, json.writeValueAsString(body));
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException exception) {
			System.out.println("会话记录没有写上 " + talk.issueNumber);
		}
	}

	private Path store(IssueTalk talk) {
		if (talk.agentId == 0) {
			return root.resolve("conversations").resolve("issue-" + talk.issueNumber + ".json");
		}
		return root.resolve("conversations").resolve("issue-" + talk.issueNumber).resolve("agent-" + talk.agentId + ".json");
	}

	private static Path resolve(Path workspace, String path) {
		if (path.startsWith("/") || path.contains("..")) {
			throw new WorkspaceException("附件不在工作区里。");
		}
		Path file = workspace.resolve(path).normalize();
		if (!file.startsWith(workspace.toAbsolutePath().normalize())) {
			throw new WorkspaceException("附件不在工作区里。");
		}
		if (!Files.exists(file)) {
			throw new WorkspaceException("附件不在工作区里。");
		}
		return file;
	}

	private static WorkerSessions.Result ready(int issueNumber) {
		return new WorkerSessions.Result(200, "{\"id\":\"issue-" + issueNumber + "\"}");
	}

	private static String detail(String message) {
		String text = safe(message);
		return "{\"detail\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}

	private static String safe(String message) {
		if (message == null || message.isBlank()) {
			return "这轮没有开始。";
		}
		String lower = message.toLowerCase();
		if (lower.contains("glpat-") || lower.contains("bearer ") || lower.contains("api_key") || message.contains("Authorization")) {
			return "这轮没有开始。";
		}
		return message.length() > 180 ? message.substring(0, 180) : message;
	}

	private static void write(OutputStream out, String frame) throws IOException {
		out.write(frame.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private final class TurnSink implements RunSink {

		private final int issueNumber;

		private final long agentId;

		private final long seq;

		private TurnSink(int issueNumber, long agentId, long seq) {
			this.issueNumber = issueNumber;
			this.agentId = agentId;
			this.seq = seq;
		}

		@Override
		public void onEvent(ExecutionEvent event) {
			applyEvent(issueNumber, agentId, seq, event);
		}

		@Override
		public void onChunk(ExecutionChunk chunk) {
			applyChunk(issueNumber, agentId, seq, chunk);
		}

		@Override
		public void onClosed(String status, String detail) {
			finish(issueNumber, agentId, seq, status == null ? "failed" : status, detail);
		}
	}

	@FunctionalInterface
	public interface Runner {
		void start(String threadId, String message, Path workspace, Map<String, String> gitlabEnv, RunSink sink,
				AgentProfile profile);
	}

	private static final class IssueTalk {

		private final int issueNumber;

		private final long agentId;

		private final Object lock = new Object();

		private final ArrayNode messages;

		private final Map<Long, List<BlockingQueue<String>>> listeners = new ConcurrentHashMap<>();

		private boolean busy;

		private String threadId;

		private IssueTalk(int issueNumber, long agentId, ArrayNode messages) {
			this.issueNumber = issueNumber;
			this.agentId = agentId;
			this.messages = messages;
		}
	}

}
