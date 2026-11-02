package dev.helix.wecom;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.helix.agentkit.HomeAgentService;
import dev.helix.agentkit.HomeAgentService.HomeMessage;
import dev.helix.agentkit.HomeAgentService.HomeView;
import dev.helix.auth.GitlabLoginEntity;
import dev.helix.auth.GitlabLoginRepository;
import dev.helix.auth.GitlabUser;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.work.ProjectWorkspaces;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class WecomBot implements org.springframework.context.SmartLifecycle {

	private final WecomProperties properties;

	private final HomeAgentService home;

	private final GitlabLoginRepository logins;

	private final ProjectRepository projects;

	private final ProjectWorkspaces workspaces;

	private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();

	private final WecomRouter router = new WecomRouter(this::ask);

	private WecomLink link;

	private volatile boolean running;

	WecomBot(WecomProperties properties, HomeAgentService home, GitlabLoginRepository logins, ProjectRepository projects,
			ProjectWorkspaces workspaces) {
		this.properties = properties;
		this.home = home;
		this.logins = logins;
		this.projects = projects;
		this.workspaces = workspaces;
	}

	@Override
	public void start() {
		if (running || !properties.enabled()) {
			return;
		}
		link = new WecomLink(properties.botId().strip(), properties.secret().strip(), router);
		link.start();
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		if (link != null) {
			link.stop();
		}
		tasks.shutdownNow();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void ask(String reqId, String userId, String question) {
		tasks.submit(() -> answer(reqId, userId, question));
	}

	private void answer(String reqId, String wecomUserId, String question) {
		String streamId = UUID.randomUUID().toString().replace("-", "");
		try {
			GitlabUser user = user(wecomUserId);
			ProjectEntity project = project();
			if (workspaces.opened(project) == null) {
				finish(reqId, streamId, "工作区还没准备好。");
				return;
			}
			send(reqId, streamId, "正在看。", false);
			home.say(project, user, question, List.of());
			String sent = "";
			long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(9);
			while (System.nanoTime() < deadline && running) {
				HomeMessage assistant = assistant(home.read(project, user));
				if (assistant == null) {
					break;
				}
				if (!assistant.text().equals(sent) && !assistant.text().isBlank()) {
					sent = assistant.text();
					send(reqId, streamId, sent, false);
				}
				if (!"running".equals(assistant.status())) {
					finish(reqId, streamId, sent.isBlank() ? "没有得到回复。" : sent);
					return;
				}
				Thread.sleep(1000);
			}
			finish(reqId, streamId, sent.isBlank() ? "这一轮还没结束。" : sent);
		}
		catch (ResponseStatusException exception) {
			finish(reqId, streamId, reason(exception));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		catch (RuntimeException exception) {
			finish(reqId, streamId, "没有答上。");
		}
	}

	private void finish(String reqId, String streamId, String text) {
		send(reqId, streamId, text == null || text.isBlank() ? "没有得到回复。" : text, true);
	}

	private void send(String reqId, String streamId, String text, boolean done) {
		if (link != null) {
			link.send(WecomFrames.stream(reqId, streamId, text, done));
		}
	}

	private GitlabUser user(String wecomUserId) {
		String external = wecomUserId == null ? "" : wecomUserId.strip();
		if (external.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到发送人。");
		}
		GitlabLoginEntity login = logins.findByUsername(external)
				.or(() -> logins.findFirstByLdapUid(external))
				.orElse(null);
		if (login != null) {
			return login.user();
		}
		return new GitlabUser(WecomIdentity.externalId(external), external, external, "");
	}

	private ProjectEntity project() {
		return projects.findAll().stream().min(Comparator.comparing(ProjectEntity::getId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "还没有项目。"));
	}

	private static HomeMessage assistant(HomeView view) {
		List<HomeMessage> messages = view.messages();
		for (int index = messages.size() - 1; index >= 0; index--) {
			if ("assistant".equals(messages.get(index).role())) {
				return messages.get(index);
			}
		}
		return null;
	}

	private static String reason(ResponseStatusException exception) {
		String text = exception.getReason();
		return text == null || text.isBlank() ? "没有答上。" : text;
	}

}
