package dev.helix.board;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.helix.worker.AgentSessionEntity;
import dev.helix.worker.AgentSessionRepository;
import dev.helix.worker.WorkerConversationClient;
import dev.helix.worker.WorkerConversationClient.Exchange;
import dev.helix.worker.WorkerRegistry;
import dev.helix.worker.WorkerRegistry.CallableWorker;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceSpecService {

	private final AgentSessionRepository sessions;

	private final WorkerRegistry registry;

	private final WorkerConversationClient workers;

	private final JsonMapper json = new JsonMapper();

	public WorkspaceSpecService(AgentSessionRepository sessions, WorkerRegistry registry, WorkerConversationClient workers) {
		this.sessions = sessions;
		this.registry = registry;
		this.workers = workers;
	}

	public SpecCheckout checkout(long projectId, int number) {
		Set<String> seen = new LinkedHashSet<>();
		CallableWorker worker = null;
		for (AgentSessionEntity session : sessions.findByProjectIdAndIssueNumberOrderByAgentIdAsc(projectId, number)) {
			if (!seen.add(session.getWorkerId())) {
				continue;
			}
			worker = registry.live(session.getWorkerId()).orElse(null);
			if (worker != null) {
				break;
			}
		}
		if (worker == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "规格在编写分支上，还没有存活的 worker。");
		}
		Exchange exchange = workers.get(worker.address(), "/sessions/" + number + "/spec");
		if (exchange.status() == 404) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, detail(exchange.body(), "这张 issue 还没有工作区。"));
		}
		if (exchange.status() != 200) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, detail(exchange.body(), "规格没有读到。"));
		}
		return parse(exchange.body());
	}

	private SpecCheckout parse(String body) {
		JsonNode node;
		try {
			node = json.readTree(body == null ? "" : body);
		}
		catch (RuntimeException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "规格没有读到。");
		}
		String branch = node.path("branch").asString("");
		String commit = node.path("commit").asString("");
		List<SpecCheckout.SpecFile> files = new java.util.ArrayList<>();
		for (JsonNode file : node.path("files")) {
			files.add(new SpecCheckout.SpecFile(file.path("path").asString(""), file.path("markdown").asString("")));
		}
		return new SpecCheckout(branch, commit, List.copyOf(files));
	}

	private static String detail(String body, String fallback) {
		if (body == null || body.isBlank()) {
			return fallback;
		}
		try {
			JsonNode node = new JsonMapper().readTree(body);
			String text = node.path("detail").asString("");
			return text.isBlank() ? fallback : text;
		}
		catch (RuntimeException exception) {
			return fallback;
		}
	}

}
