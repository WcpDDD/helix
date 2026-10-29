package dev.helix.agentkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.auth.GitlabUser;
import dev.helix.board.AgentEntity;
import dev.helix.board.AgentRepository;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.board.SampleAgents;
import dev.helix.work.ProjectWorkspaces;

import io.agentkit.api.execution.ExecutionChunk;
import io.agentkit.api.execution.ExecutionEvent;
import io.agentkit.api.execution.ExecutionPhase;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HomeAgentService {

	private final AgentKitRuntime runtime;

	private final AgentRepository agents;

	private final SampleAgents samples;

	private final AgentKitProperties properties;

	private final ProjectWorkspaces workspaces;

	private final GitlabSessionCredential credential;

	private final HomeObjectStore objects;

	private final HomeSessionRepository sessions;

	private final TransactionTemplate transactions;

	private final Path talks;

	private final JsonMapper json = new JsonMapper();

	private final Map<Key, Talk> open = new ConcurrentHashMap<>();

	public HomeAgentService(AgentKitProperties properties, ProjectWorkspaces workspaces, GitlabSessionCredential credential,
			HomeObjectStore objects, HomeSessionRepository sessions, PlatformTransactionManager transactions,
			AgentRepository agents, ProjectRepository projects, SampleAgents samples) {
		Path base = Path.of(blank(properties.workspace()) ? System.getProperty("user.home") + "/helix-agents" : properties.workspace());
		AgentKitRuntime hands = new AgentKitRuntime(properties, base.resolve("home-hands"), objects::openQuietly);
		HomeDelegate delegate = new HomeDelegate(agents, projects, workspaces, hands);
		this.runtime = new AgentKitRuntime(properties, base.resolve("home"), () -> null, delegate.tools());
		this.agents = agents;
		this.samples = samples;
		this.properties = properties;
		this.workspaces = workspaces;
		this.credential = credential;
		this.objects = objects;
		this.sessions = sessions;
		this.transactions = new TransactionTemplate(transactions);
		this.talks = base.resolve("home-talks");
	}

	@PostConstruct
	void importLegacyFiles() {
		if (!Files.isDirectory(talks)) {
			return;
		}
		try (var listed = Files.list(talks)) {
			for (Path file : listed.filter(path -> path.getFileName().toString().matches("\\d+-\\d+\\.json")).toList()) {
				importLegacy(file);
			}
		}
		catch (IOException exception) {
			System.out.println("旧的首页会话没有迁进数据库");
		}
	}

	public HomeView read(ProjectEntity project, GitlabUser user) {
		Talk talk = talk(project, user);
		return view(project, user, talk);
	}

	public void stream(ProjectEntity project, GitlabUser user, String sessionId, OutputStream out) throws IOException {
		Talk talk = talk(project, user);
		Ear ear = new Ear();
		String snapshot;
		synchronized (talk) {
			boolean same = blank(sessionId) || sessionId.equals(talk.sessionId);
			if (!same || !talk.busy) {
				snapshot = null;
			}
			else {
				snapshot = frame("snapshot", live(talk));
				talk.ears.add(ear);
			}
		}
		if (snapshot == null) {
			write(out, frame("done", Map.of("status", "done")));
			return;
		}
		try {
			write(out, snapshot);
			while (true) {
				Ear.Frame next = ear.poll();
				if (next == null) {
					write(out, ": ping\n\n");
					continue;
				}
				write(out, next.body());
				if (next.end()) {
					return;
				}
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		finally {
			ear.close();
			synchronized (talk) {
				talk.ears.remove(ear);
			}
		}
	}

	public HomeView open(ProjectEntity project, GitlabUser user, String sessionId) {
		if (blank(sessionId)) {
			return read(project, user);
		}
		Key key = new Key(user.id(), project.getId());
		Talk current = talk(project, user);
		synchronized (current) {
			if (sessionId.equals(current.sessionId)) {
				return view(project, user, current);
			}
			if (current.busy) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "上一轮还在进行。");
			}
		}
		HomeSessionEntity entity = sessions.findByIdAndProjectIdAndUserId(sessionId, project.getId(), user.id())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个会话。"));
		Talk loaded = from(entity);
		open.put(key, loaded);
		markCurrent(project, user, loaded);
		return view(project, user, loaded);
	}

	public HomeView reset(ProjectEntity project, GitlabUser user) {
		Talk talk = talk(project, user);
		synchronized (talk) {
			if (talk.busy) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "上一轮还在进行。");
			}
			if (!talk.messages.isEmpty()) {
				save(project, user, talk);
			}
		}
		Talk fresh = new Talk();
		open.put(new Key(user.id(), project.getId()), fresh);
		return view(project, user, fresh);
	}

	public HomeView say(ProjectEntity project, GitlabUser user, String text, List<HomeFile> files) {
		Path workspace = opened(project);
		if (!properties.ready()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AGENTKIT_MODEL 和 AGENTKIT_API_KEY 还没配。");
		}
		String visible = text == null ? "" : text.strip();
		List<HomeFile> attached = accepted(files);
		if (visible.isEmpty() && attached.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要说一句让这个 agent 做的事。");
		}
		samples.ensure(project);
		String prompt = visible.isEmpty() ? "请看这些附件。" : visible;
		if (!attached.isEmpty()) {
			prompt = prompt + "\n\n附件：\n" + addresses(attached);
		}
		Talk talk = talk(project, user);
		String thread;
		synchronized (talk) {
			if (talk.busy) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "上一轮还在进行。");
			}
			talk.busy = true;
			talk.messages.add(new Line("user", "done", visible, "", List.of(), attached, Instant.now().toString()));
			talk.messages.add(new Line("assistant", "running", "", "", List.of(), List.of(), Instant.now().toString()));
			thread = talk.threadId;
			try {
				save(project, user, talk);
			}
			catch (RuntimeException exception) {
				talk.busy = false;
				talk.messages.remove(talk.messages.size() - 1);
				talk.messages.remove(talk.messages.size() - 1);
				throw exception;
			}
		}
		List<URI> urls = new ArrayList<>();
		for (HomeFile file : attached) {
			urls.add(URI.create(file.url()));
		}
		try {
			runtime.begin(thread, roster(project), user.username(), prompt, workspace, Map.of(), new Sink(project, user, talk), urls);
		}
		catch (RuntimeException exception) {
			finish(talk, "failed", null, exception.getMessage());
			save(project, user, talk);
			throw exception;
		}
		return view(project, user, talk);
	}

	private String roster(ProjectEntity project) {
		StringBuilder text = new StringBuilder();
		text.append("你只负责把用户的请求委托给当前项目里的一个 agent。你没有读文件和执行命令的能力。\n");
		text.append("必须调用 delegate。agent 只能填下面名单里的名字，task 写清要完成的事。拿到结果后，只把结果告诉用户。\n");
		text.append("用户消息里的附件地址原样填进 uri。被委托的 agent 能识别图片。没有附件就留空。\n");
		text.append("能对上专职 agent 就交给它，对不上就交给兜底答疑。\n");
		text.append("可用 agent：\n");
		for (AgentEntity agent : agents.findByProject_IdOrderByNameAsc(project.getId())) {
			text.append("- ").append(agent.getName());
			String line = firstLine(agent.getBody());
			if (!line.isEmpty()) {
				text.append('：').append(line);
			}
			text.append('\n');
		}
		return text.toString();
	}

	private static String firstLine(String body) {
		if (body == null) {
			return "";
		}
		String line = body.strip();
		int breakAt = line.indexOf('\n');
		if (breakAt >= 0) {
			line = line.substring(0, breakAt).strip();
		}
		return line.length() <= 80 ? line : line.substring(0, 80);
	}

	private static String addresses(List<HomeFile> files) {
		StringBuilder text = new StringBuilder();
		for (HomeFile file : files) {
			text.append(file.name()).append(' ').append(file.url()).append('\n');
		}
		return text.toString().strip();
	}

	private List<HomeFile> accepted(List<HomeFile> files) {
		if (files == null || files.isEmpty()) {
			return List.of();
		}
		List<HomeFile> accepted = new ArrayList<>();
		for (HomeFile file : files) {
			if (file == null || blank(file.url())) {
				continue;
			}
			URI uri;
			try {
				uri = URI.create(file.url().strip());
			}
			catch (IllegalArgumentException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件地址不对。");
			}
			if (!objects.owned(uri)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件地址不对。");
			}
			accepted.add(new HomeFile(blank(file.name()) ? "附件" : file.name().strip(), uri.toString()));
		}
		return List.copyOf(accepted);
	}

	private Talk talk(ProjectEntity project, GitlabUser user) {
		return open.computeIfAbsent(new Key(user.id(), project.getId()), key -> load(project, user));
	}

	private Talk load(ProjectEntity project, GitlabUser user) {
		return sessions.findFirstByProjectIdAndUserIdAndCurrentSessionTrueOrderByUpdatedAtDesc(project.getId(), user.id())
				.or(() -> sessions.findByProjectIdAndUserIdOrderByUpdatedAtDesc(project.getId(), user.id()).stream().findFirst())
				.map(this::from)
				.orElseGet(Talk::new);
	}

	private Talk from(HomeSessionEntity entity) {
		Talk talk = new Talk();
		talk.sessionId = entity.getId();
		talk.threadId = entity.getThreadId();
		List<HomeMessage> messages;
		try {
			messages = json.readValue(entity.getMessagesJson(), json.getTypeFactory().constructCollectionType(List.class, HomeMessage.class));
		}
		catch (RuntimeException exception) {
			return talk;
		}
		if (messages == null) {
			return talk;
		}
		for (HomeMessage message : messages) {
			Line line = Line.from(message);
			if ("running".equals(line.status)) {
				line.status = "failed";
				if (blank(line.text)) {
					line.text = "这轮中断了。";
				}
			}
			talk.messages.add(line);
		}
		return talk;
	}

	private void save(ProjectEntity project, GitlabUser user, Talk talk) {
		Stored stored;
		synchronized (talk) {
			if (talk.messages.isEmpty()) {
				return;
			}
			stored = new Stored(talk.sessionId, talk.threadId, talk.copy());
		}
		try {
			transactions.executeWithoutResult(status -> write(project, user, stored));
		}
		catch (RuntimeException exception) {
			if (exception instanceof ResponseStatusException status) {
				throw status;
			}
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "会话没有记下。");
		}
	}

	private void write(ProjectEntity project, GitlabUser user, Stored stored) {
		HomeSessionEntity entity = sessions.findById(stored.sessionId()).orElseGet(
				() -> new HomeSessionEntity(stored.sessionId(), project.getId(), user.id(), user.username()));
		String at = stored.messages().isEmpty() ? Instant.now().toString() : stored.messages().get(stored.messages().size() - 1).at();
		entity.remember(stored.threadId(), json.writeValueAsString(stored.messages()), blank(at) ? Instant.now().toString() : at, true);
		sessions.save(entity);
		for (HomeSessionEntity other : sessions.findByProjectIdAndUserIdOrderByUpdatedAtDesc(project.getId(), user.id())) {
			if (!other.getId().equals(entity.getId()) && other.isCurrentSession()) {
				other.current(false);
				sessions.save(other);
			}
		}
	}

	private void markCurrent(ProjectEntity project, GitlabUser user, Talk talk) {
		transactions.executeWithoutResult(status -> {
			for (HomeSessionEntity other : sessions.findByProjectIdAndUserIdOrderByUpdatedAtDesc(project.getId(), user.id())) {
				boolean current = other.getId().equals(talk.sessionId);
				if (other.isCurrentSession() != current) {
					other.current(current);
					sessions.save(other);
				}
			}
		});
	}

	private void importLegacy(Path file) {
		String name = file.getFileName().toString();
		int split = name.indexOf('-');
		int dot = name.lastIndexOf('.');
		long userId;
		long projectId;
		try {
			userId = Long.parseLong(name.substring(0, split));
			projectId = Long.parseLong(name.substring(split + 1, dot));
		}
		catch (RuntimeException exception) {
			return;
		}
		try {
			Stored stored = json.readValue(Files.readString(file), Stored.class);
			if (stored == null || blank(stored.sessionId()) || sessions.existsById(stored.sessionId())) {
				Files.move(file, file.resolveSibling(name + ".imported"), StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			String at = stored.messages() == null || stored.messages().isEmpty() ? Instant.now().toString()
					: stored.messages().get(stored.messages().size() - 1).at();
			HomeSessionEntity entity = new HomeSessionEntity(stored.sessionId(), projectId, userId, "");
			entity.remember(stored.threadId(), json.writeValueAsString(stored.messages() == null ? List.of() : stored.messages()), at, true);
			sessions.save(entity);
			Files.move(file, file.resolveSibling(name + ".imported"), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException | RuntimeException exception) {
			System.out.println("旧的首页会话没有迁进数据库");
		}
	}

	private HomeView view(ProjectEntity project, GitlabUser user, Talk talk) {
		Path opened = workspaces.opened(project);
		synchronized (talk) {
			return new HomeView(opened == null ? "" : opened.toString(), opened == null, talk.sessionId, user.id(), user.username(),
					talk.copy(), history(project, user, talk));
		}
	}

	private List<HomeSessionItem> history(ProjectEntity project, GitlabUser user, Talk talk) {
		List<HomeSessionItem> items = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		items.add(new HomeSessionItem(talk.sessionId, title(talk.copy()), stamp(talk.copy()), true));
		seen.add(talk.sessionId);
		for (HomeSessionEntity entity : sessions.findByProjectIdAndUserIdOrderByUpdatedAtDesc(project.getId(), user.id())) {
			if (!seen.add(entity.getId())) {
				continue;
			}
			List<HomeMessage> messages = messagesOf(entity);
			items.add(new HomeSessionItem(entity.getId(), title(messages), blank(entity.getUpdatedAt()) ? stamp(messages) : entity.getUpdatedAt(),
					false));
		}
		return List.copyOf(items);
	}

	private List<HomeMessage> messagesOf(HomeSessionEntity entity) {
		try {
			List<HomeMessage> messages = json.readValue(entity.getMessagesJson(),
					json.getTypeFactory().constructCollectionType(List.class, HomeMessage.class));
			return messages == null ? List.of() : messages;
		}
		catch (RuntimeException exception) {
			return List.of();
		}
	}

	private static String title(List<HomeMessage> messages) {
		for (HomeMessage message : messages) {
			if ("user".equals(message.role()) && !blank(message.text())) {
				String text = message.text().strip().replaceAll("\\s+", " ");
				return text.length() <= 24 ? text : text.substring(0, 24);
			}
		}
		return "新会话";
	}

	private static String stamp(List<HomeMessage> messages) {
		if (messages.isEmpty() || blank(messages.get(0).at())) {
			return Instant.now().toString();
		}
		return messages.get(0).at();
	}

	private Path opened(ProjectEntity project) {
		Path opened = workspaces.opened(project);
		if (opened != null) {
			return opened;
		}
		String token = credential.current();
		if (!blank(token)) {
			workspaces.prepare(project, token);
			opened = workspaces.opened(project);
		}
		if (opened == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "主分支还在准备。");
		}
		return opened;
	}

	private void finish(Talk talk, String status, String text, String failure) {
		synchronized (talk) {
			boolean running = talk.busy;
			talk.busy = false;
			if (talk.messages.isEmpty()) {
				if (running) {
					emit(talk, "done", Map.of("status", status));
				}
				return;
			}
			Line last = talk.messages.get(talk.messages.size() - 1);
			if (!"assistant".equals(last.role)) {
				if (running) {
					emit(talk, "done", Map.of("status", status));
				}
				return;
			}
			if (!blank(text) && (blank(last.text) || text.length() >= last.text.length())) {
				last.text = text;
			}
			if ("failed".equals(status) && blank(last.text) && !blank(failure)) {
				last.text = failure;
			}
			last.status = status;
			for (ToolLine tool : last.tools) {
				if ("running".equals(tool.status)) {
					tool.status = "failed".equals(status) ? "failed" : "done";
				}
			}
			if (running) {
				emit(talk, "done", live(talk));
			}
		}
	}

	private Live live(Talk talk) {
		if (talk.messages.isEmpty()) {
			return new Live("", "", "done", List.of());
		}
		Line last = talk.messages.get(talk.messages.size() - 1);
		if (!"assistant".equals(last.role)) {
			return new Live("", "", "done", List.of());
		}
		return new Live(last.text, last.reasoning, last.status, last.view().tools());
	}

	private void emit(Talk talk, String name, Object body) {
		if (talk.ears.isEmpty()) {
			return;
		}
		String frame = frame(name, body);
		boolean end = "done".equals(name);
		for (Ear ear : talk.ears) {
			ear.push(frame, end);
		}
	}

	private String frame(String name, Object body) {
		return "event: " + name + "\ndata: " + json.writeValueAsString(body) + "\n\n";
	}

	private static void write(OutputStream out, String frame) throws IOException {
		out.write(frame.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private String agentOf(String arguments) {
		if (blank(arguments)) {
			return "";
		}
		try {
			JsonNode agent = json.readTree(arguments).get("agent");
			return agent == null || agent.isNull() ? "" : agent.asText("").strip();
		}
		catch (RuntimeException exception) {
			return "";
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	public record HomeView(String workspace, boolean preparing, String sessionId, long userId, String username,
			List<HomeMessage> messages, List<HomeSessionItem> sessions) {
	}

	public record HomeSessionItem(String id, String title, String at, boolean current) {
	}

	public record HomeMessage(String id, String role, String status, String text, String reasoning, List<HomeTool> tools,
			List<HomeFile> files, String at) {
	}

	public record HomeTool(String id, String name, String arguments, String observation, String status) {
	}

	public record HomeFile(String name, String url) {
	}

	public record Live(String text, String reasoning, String status, List<HomeTool> tools) {
	}

	private record Stored(String sessionId, String threadId, List<HomeMessage> messages) {
	}

	private record Key(long userId, long projectId) {
	}

	private static final class Line {

		private final String id;

		private final String role;

		private String status;

		private String text;

		private String reasoning;

		private final List<ToolLine> tools;

		private final List<HomeFile> files;

		private final String at;

		private Line(String role, String status, String text, String reasoning, List<ToolLine> tools, List<HomeFile> files, String at) {
			this(UUID.randomUUID().toString(), role, status, text, reasoning, tools, files, at);
		}

		private Line(String id, String role, String status, String text, String reasoning, List<ToolLine> tools, List<HomeFile> files,
				String at) {
			this.id = id;
			this.role = role;
			this.status = status;
			this.text = text == null ? "" : text;
			this.reasoning = reasoning == null ? "" : reasoning;
			this.tools = new ArrayList<>(tools == null ? List.of() : tools);
			this.files = List.copyOf(files == null ? List.of() : files);
			this.at = at == null ? Instant.now().toString() : at;
		}

		private static Line from(HomeMessage message) {
			List<ToolLine> tools = new ArrayList<>();
			if (message.tools() != null) {
				for (HomeTool tool : message.tools()) {
					tools.add(new ToolLine(tool.id(), tool.name(), tool.arguments(), tool.observation(), tool.status()));
				}
			}
			return new Line(message.id(), message.role(), message.status(), message.text(), message.reasoning(), tools, message.files(),
					message.at());
		}

		private HomeMessage view() {
			List<HomeTool> shown = new ArrayList<>();
			for (ToolLine tool : tools) {
				shown.add(new HomeTool(tool.id, tool.name, tool.arguments, tool.observation, tool.status));
			}
			return new HomeMessage(id, role, status, text, reasoning, List.copyOf(shown), files, at);
		}

	}

	private static final class ToolLine {

		private final String id;

		private final String name;

		private String arguments;

		private String observation;

		private String status;

		private ToolLine(String id, String name, String arguments, String observation, String status) {
			this.id = blank(id) ? UUID.randomUUID().toString() : id;
			this.name = blank(name) ? "工具" : name;
			this.arguments = arguments == null ? "" : arguments;
			this.observation = observation == null ? "" : observation;
			this.status = blank(status) ? "done" : status;
		}

		private static boolean blank(String value) {
			return value == null || value.isBlank();
		}

	}

	private static final class Talk {

		private String sessionId = UUID.randomUUID().toString();

		private final List<Line> messages = new ArrayList<>();

		private String threadId;

		private boolean busy;

		private final List<Ear> ears = new ArrayList<>();

		private List<HomeMessage> copy() {
			List<HomeMessage> copy = new ArrayList<>();
			for (Line message : messages) {
				copy.add(message.view());
			}
			return List.copyOf(copy);
		}

	}

	private static final class Ear {

		private final ReentrantLock lock = new ReentrantLock();

		private final Condition arrived = lock.newCondition();

		private final ArrayDeque<Frame> frames = new ArrayDeque<>();

		private boolean closed;

		private void push(String body, boolean end) {
			lock.lock();
			try {
				if (closed) {
					return;
				}
				frames.add(new Frame(body, end));
				arrived.signalAll();
			}
			finally {
				lock.unlock();
			}
		}

		private Frame poll() throws InterruptedException {
			lock.lock();
			try {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
				while (frames.isEmpty() && !closed) {
					long left = deadline - System.nanoTime();
					if (left <= 0) {
						return null;
					}
					arrived.awaitNanos(left);
				}
				return frames.poll();
			}
			finally {
				lock.unlock();
			}
		}

		private void close() {
			lock.lock();
			try {
				closed = true;
				arrived.signalAll();
			}
			finally {
				lock.unlock();
			}
		}

		private record Frame(String body, boolean end) {
		}

	}

	private final class Sink implements AgentKitRuntime.RunSink {

		private final ProjectEntity project;

		private final GitlabUser user;

		private final Talk talk;

		private Sink(ProjectEntity project, GitlabUser user, Talk talk) {
			this.project = project;
			this.user = user;
			this.talk = talk;
		}

		@Override
		public void onEvent(ExecutionEvent event) {
			if (event instanceof ExecutionEvent.RunStarted started) {
				synchronized (talk) {
					talk.threadId = started.threadId();
				}
			}
			if (event instanceof ExecutionEvent.ToolStarted started && "delegate".equals(started.name())) {
				String name = agentOf(started.arguments());
				if (!name.isEmpty()) {
					synchronized (talk) {
						Line last = assistant();
						if (last != null) {
							ToolLine tool = new ToolLine(started.toolCallId(), name, "", "", "running");
							int index = -1;
							for (int i = 0; i < last.tools.size(); i++) {
								if (started.toolCallId().equals(last.tools.get(i).id)) {
									index = i;
									break;
								}
							}
							if (index >= 0) {
								last.tools.set(index, tool);
							}
							else {
								last.tools.add(tool);
							}
							emit(talk, "tool", Map.of("id", tool.id, "name", tool.name, "arguments", "", "observation", "", "status", "running"));
						}
					}
				}
			}
			if (event instanceof ExecutionEvent.RunEnded ended) {
				String status = ended.phase() == ExecutionPhase.FAILED ? "failed" : "done";
				finish(talk, status, ended.assistantText(), ended.failureMessage());
				save(project, user, talk);
			}
		}

		@Override
		public void onChunk(ExecutionChunk chunk) {
			synchronized (talk) {
				Line last = assistant();
				if (last == null) {
					return;
				}
				if (chunk.text() != null && !chunk.text().isEmpty()) {
					last.text = last.text + chunk.text();
					emit(talk, "text", Map.of("text", chunk.text()));
				}
			}
		}

		@Override
		public void onClosed(String status, String detail) {
			finish(talk, "failed".equals(status) ? "failed" : "done", null, detail);
			save(project, user, talk);
		}

		private Line assistant() {
			if (talk.messages.isEmpty()) {
				return null;
			}
			Line last = talk.messages.get(talk.messages.size() - 1);
			return "assistant".equals(last.role) ? last : null;
		}

	}

}
