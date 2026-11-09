package dev.helix.observe;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.helix.agentkit.HomeAgentService;
import dev.helix.agentkit.HomeAgentService.HomeMessage;
import dev.helix.agentkit.HomeAgentService.HomeTool;
import dev.helix.agentkit.HomeAgentService.ServerRun;
import dev.helix.board.ProjectEntity;
import dev.helix.worker.AgentPresence;
import dev.helix.worker.AgentSessionEntity;
import dev.helix.worker.AgentSessionRepository;
import dev.helix.worker.WorkerConversationClient;
import dev.helix.worker.WorkerRegistry;
import dev.helix.worker.WorkerRegistry.CallableWorker;

import org.springframework.stereotype.Service;

@Service
public class ObserveService {

	private static final int CLIP = 4000;

	private final AgentPresence presence;

	private final WorkerRegistry registry;

	private final WorkerConversationClient conversations;

	private final AgentSessionRepository sessions;

	private final HomeAgentService home;

	private final JsonMapper json = new JsonMapper();

	public ObserveService(AgentPresence presence, WorkerRegistry registry, WorkerConversationClient conversations,
			AgentSessionRepository sessions, HomeAgentService home) {
		this.presence = presence;
		this.registry = registry;
		this.conversations = conversations;
		this.sessions = sessions;
		this.home = home;
	}

	public List<RunCard> runs(ProjectEntity project) {
		List<RunCard> cards = new ArrayList<>();
		for (AgentPresence.Sight sight : presence.sights()) {
			if (!belongs(project.getId(), sight)) {
				continue;
			}
			CallableWorker worker = registry.live(sight.workerId()).orElse(null);
			String name = worker == null ? sight.workerId() : worker.name();
			String activity = "进行中";
			String at = "";
			if (worker != null) {
				JsonNode listed = read(worker, sight.issue(), sight.agent(), "/conversations");
				if (listed != null) {
					for (JsonNode message : listed.path("messages")) {
						if (!"assistant".equals(message.path("role").asText()) || !"running".equals(message.path("status").asText())) {
							continue;
						}
						String preview = message.path("preview").asText("").strip();
						if (!preview.isEmpty()) {
							activity = preview;
						}
						at = stamp(message.path("createdAt").asLong(0));
					}
				}
				else {
					activity = "对话暂时读不到";
				}
			}
			else {
				activity = "worker 不存活";
			}
			cards.add(new RunCard("worker", sight.workerId(), name, sight.issue(), blank(sight.agent()) ? "" : sight.agent(), "",
					"", title(sight.issue(), sight.agent()), activity, at));
		}
		for (ServerRun run : home.running(project.getId())) {
			cards.add(new RunCard("server", "", "服务端", 0, "首页", run.sessionId(), run.username(), title(run.messages()),
					activityOf(run.messages()), started(run.messages())));
		}
		cards.sort(Comparator.comparing(RunCard::at).reversed());
		return List.copyOf(cards);
	}

	public RunDetail open(ProjectEntity project, String place, String workerId, int issue, String agent, String sessionId) {
		if ("server".equals(place)) {
			return server(project.getId(), sessionId);
		}
		if (!"worker".equals(place)) {
			return new RunDetail("worker", "", 0, "", "", "", "", "没有这条运行。", List.of());
		}
		AgentPresence.Sight sight = presence.sights().stream()
				.filter(item -> item.workerId().equals(workerId) && item.issue() == issue && same(item.agent(), agent))
				.findFirst()
				.orElse(null);
		if (sight == null || !belongs(project.getId(), sight)) {
			return new RunDetail("worker", "", issue, agent == null ? "" : agent, "", title(issue, agent), "", "这轮已经结束。", List.of());
		}
		CallableWorker worker = registry.live(sight.workerId()).orElse(null);
		String name = worker == null ? sight.workerId() : worker.name();
		if (worker == null) {
			return new RunDetail("worker", name, issue, sight.agent(), "", title(issue, sight.agent()), "worker 不存活", "这个 worker 不存活。",
					List.of());
		}
		JsonNode listed = read(worker, issue, sight.agent(), "/conversations");
		if (listed == null) {
			return new RunDetail("worker", name, issue, sight.agent(), "", title(issue, sight.agent()), "对话暂时读不到", "对话没有读到。",
					List.of());
		}
		List<Turn> turns = new ArrayList<>();
		for (JsonNode item : listed.path("messages")) {
			long seq = item.path("seq").asLong(0);
			JsonNode full = seq <= 0 ? null : read(worker, issue, sight.agent(), "/conversations/" + seq);
			turns.add(full == null ? previewTurn(item) : turn(full));
		}
		String note = listed.path("hasMore").asBoolean(false) ? "更早的对话没有展开。" : "";
		return new RunDetail("worker", name, issue, sight.agent(), "", title(issue, sight.agent()), activityOfTurns(turns), note, turns);
	}

	private RunDetail server(long projectId, String sessionId) {
		ServerRun run = home.running(projectId).stream()
				.filter(item -> item.sessionId().equals(sessionId))
				.findFirst()
				.orElse(null);
		if (run == null) {
			return new RunDetail("server", "服务端", 0, "首页", "", "首页会话", "", "这轮已经结束。", List.of());
		}
		List<Turn> turns = new ArrayList<>();
		for (HomeMessage message : run.messages()) {
			List<ToolView> tools = new ArrayList<>();
			if (message.tools() != null) {
				for (HomeTool tool : message.tools()) {
					tools.add(new ToolView(tool.name(), tool.status(), clip(tool.arguments()), clip(tool.observation())));
				}
			}
			turns.add(new Turn(message.role(), message.status(), clip(message.text()), clip(message.reasoning()), message.at(), tools));
		}
		return new RunDetail("server", "服务端", 0, "首页", run.username(), title(run.messages()), activityOf(run.messages()), "", turns);
	}

	private boolean belongs(long projectId, AgentPresence.Sight sight) {
		List<AgentSessionEntity> rows = sessions.findByWorkerIdAndIssueNumber(sight.workerId(), sight.issue());
		if (rows.isEmpty()) {
			return true;
		}
		return rows.stream().anyMatch(row -> row.getProjectId() == projectId && same(row.getAgentName(), sight.agent()));
	}

	private JsonNode read(CallableWorker worker, int issue, String agent, String suffix) {
		String path = "/sessions/" + issue + suffix + query(agent);
		WorkerConversationClient.Exchange exchange = conversations.get(worker.address(), path);
		if (exchange.status() < 200 || exchange.status() >= 300 || exchange.body() == null || exchange.body().isBlank()) {
			return null;
		}
		try {
			return json.readTree(exchange.body());
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	private Turn turn(JsonNode message) {
		StringBuilder text = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		List<ToolView> tools = new ArrayList<>();
		for (JsonNode part : message.path("parts")) {
			String kind = part.path("kind").asText("");
			if ("text".equals(kind)) {
				text.append(part.path("text").asText(""));
			}
			else if ("thinking".equals(kind)) {
				reasoning.append(part.path("text").asText(""));
			}
			else if ("tool".equals(kind)) {
				tools.add(new ToolView(part.path("name").asText("工具"), part.path("status").asText("done"),
						clip(part.path("arguments").asText("")), clip(part.path("observation").asText(""))));
			}
		}
		return new Turn(message.path("role").asText(""), message.path("status").asText(""), clip(text.toString()),
				clip(reasoning.toString()), stamp(message.path("createdAt").asLong(0)), tools);
	}

	private static Turn previewTurn(JsonNode message) {
		return new Turn(message.path("role").asText(""), message.path("status").asText(""), message.path("preview").asText(""), "",
				stamp(message.path("createdAt").asLong(0)), List.of());
	}

	private static String activityOf(List<HomeMessage> messages) {
		for (int index = messages.size() - 1; index >= 0; index--) {
			HomeMessage message = messages.get(index);
			if (!"assistant".equals(message.role())) {
				continue;
			}
			if (message.tools() != null) {
				for (int toolIndex = message.tools().size() - 1; toolIndex >= 0; toolIndex--) {
					HomeTool tool = message.tools().get(toolIndex);
					if ("running".equals(tool.status()) && tool.name() != null && !tool.name().isBlank()) {
						return tool.name();
					}
				}
			}
			if ("running".equals(message.status())) {
				return "正在回复";
			}
		}
		return "进行中";
	}

	private static String activityOfTurns(List<Turn> turns) {
		for (int index = turns.size() - 1; index >= 0; index--) {
			Turn turn = turns.get(index);
			if (!"assistant".equals(turn.role())) {
				continue;
			}
			for (int toolIndex = turn.tools().size() - 1; toolIndex >= 0; toolIndex--) {
				ToolView tool = turn.tools().get(toolIndex);
				if ("running".equals(tool.status()) && !tool.name().isBlank()) {
					return tool.name();
				}
			}
			if ("running".equals(turn.status())) {
				return "正在回复";
			}
		}
		return "进行中";
	}

	private static String title(List<HomeMessage> messages) {
		for (HomeMessage message : messages) {
			if ("user".equals(message.role()) && message.text() != null && !message.text().isBlank()) {
				String text = message.text().strip().replaceAll("\\s+", " ");
				return text.length() <= 42 ? text : text.substring(0, 42);
			}
		}
		return "首页会话";
	}

	private static String started(List<HomeMessage> messages) {
		for (int index = messages.size() - 1; index >= 0; index--) {
			HomeMessage message = messages.get(index);
			if ("assistant".equals(message.role()) && "running".equals(message.status()) && message.at() != null) {
				return message.at();
			}
		}
		return messages.isEmpty() || messages.get(0).at() == null ? "" : messages.get(0).at();
	}

	private static String title(int issue, String agent) {
		String name = blank(agent) ? "agent" : agent.strip();
		return "#" + issue + " · " + name;
	}

	private static String query(String agent) {
		if (blank(agent)) {
			return "";
		}
		return "?agent=" + URLEncoder.encode(agent.strip(), StandardCharsets.UTF_8);
	}

	private static String stamp(long millis) {
		if (millis <= 0) {
			return "";
		}
		return Instant.ofEpochMilli(millis).toString();
	}

	private static boolean same(String left, String right) {
		String one = left == null ? "" : left.strip();
		String two = right == null ? "" : right.strip();
		return one.equals(two);
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String clip(String value) {
		if (value == null) {
			return "";
		}
		if (value.length() <= CLIP) {
			return value;
		}
		return value.substring(0, CLIP) + "\n…后面还有内容。";
	}

	public record RunCard(String place, String workerId, String workerName, int issue, String agent, String sessionId,
			String username, String title, String activity, String at) {
	}

	public record RunDetail(String place, String workerName, int issue, String agent, String username, String title,
			String activity, String note, List<Turn> turns) {
	}

	public record Turn(String role, String status, String text, String reasoning, String at, List<ToolView> tools) {
	}

	public record ToolView(String name, String status, String arguments, String observation) {
	}

}
