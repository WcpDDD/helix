package dev.helix.wecom;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class WecomLink {

	private final String botId;

	private final String secret;

	private final WecomRouter router;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "wecom-bot");
		thread.setDaemon(true);
		return thread;
	});

	private final StringBuilder inbound = new StringBuilder();

	private volatile WebSocket socket;

	private volatile boolean stopping;

	private volatile boolean authenticated;

	private volatile long holdUntil;

	private final AtomicInteger epoch = new AtomicInteger();

	private CompletableFuture<WebSocket> tail = CompletableFuture.completedFuture(null);

	private ScheduledFuture<?> heartbeat;

	private ScheduledFuture<?> reconnectTask;

	private String subscribeReq = "";

	private int attempt;

	WecomLink(String botId, String secret, WecomRouter router) {
		this.botId = botId;
		this.secret = secret;
		this.router = router;
	}

	void start() {
		connect();
	}

	void stop() {
		stopping = true;
		ScheduledFuture<?> pulse = heartbeat;
		if (pulse != null) {
			pulse.cancel(false);
		}
		WebSocket current = socket;
		if (current != null) {
			current.sendClose(WebSocket.NORMAL_CLOSURE, "stop");
		}
		clock.shutdownNow();
	}

	void send(String text) {
		WebSocket current = socket;
		if (current == null || stopping) {
			return;
		}
		synchronized (this) {
			tail = tail.handle((ok, error) -> null).thenCompose(ignored -> current.sendText(text, true));
		}
	}

	private void connect() {
		if (stopping) {
			return;
		}
		int mine = epoch.incrementAndGet();
		http.newWebSocketBuilder().buildAsync(URI.create(WecomFrames.ENDPOINT), new Socket(mine)).whenComplete((webSocket, error) -> {
			if (stopping || mine != epoch.get()) {
				if (webSocket != null) {
					webSocket.abort();
				}
				return;
			}
			if (error != null) {
				System.out.println("企业微信长连接没有连上");
				scheduleReconnect();
			}
		});
	}

	private void subscribed() {
		authenticated = true;
		attempt = 0;
		ScheduledFuture<?> previous = heartbeat;
		if (previous != null) {
			previous.cancel(false);
		}
		heartbeat = clock.scheduleAtFixedRate(this::ping, 30, 30, TimeUnit.SECONDS);
		System.out.println("企业微信长连接已订阅");
	}

	private void ping() {
		if (authenticated && socket != null) {
			send(WecomFrames.ping(UUID.randomUUID().toString()));
		}
	}

	private void kicked() {
		holdUntil = System.currentTimeMillis() + 60_000;
		System.out.println("企业微信长连接被新连接替换，一分钟后再试");
	}

	private void authFailed(String errmsg) {
		stopping = true;
		System.out.println("企业微信长连接订阅没有通过：" + (errmsg == null || errmsg.isBlank() ? "未知错误" : errmsg));
		WebSocket current = socket;
		if (current != null) {
			current.abort();
		}
	}

	private void scheduleReconnect() {
		if (stopping) {
			return;
		}
		synchronized (this) {
			if (reconnectTask != null && !reconnectTask.isDone()) {
				return;
			}
			attempt++;
			long backoff = Math.min(30, 1L << Math.min(attempt, 5)) * 1000;
			long wait = Math.max(backoff, holdUntil - System.currentTimeMillis());
			reconnectTask = clock.schedule(this::connect, Math.max(wait, 1000), TimeUnit.MILLISECONDS);
		}
	}

	private void incoming(String text) {
		WecomFrames.Frame frame = WecomFrames.read(text);
		if (frame.errcode() != null && subscribeReq.equals(frame.reqId())) {
			if (frame.errcode() == 0) {
				subscribed();
			}
			else {
				authFailed(frame.errmsg());
			}
			return;
		}
		if (frame.errcode() != null && frame.errcode() != 0) {
			System.out.println("企业微信回复没有送出：" + frame.errmsg());
			return;
		}
		if ("aibot_event_callback".equals(frame.cmd()) && "disconnected_event".equals(frame.eventType())) {
			kicked();
			return;
		}
		router.accept(frame, this::send);
	}

	private final class Socket implements WebSocket.Listener {

		private final int mine;

		private Socket(int mine) {
			this.mine = mine;
		}

		private boolean current() {
			return !stopping && mine == epoch.get();
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			if (!current()) {
				webSocket.abort();
				return;
			}
			socket = webSocket;
			authenticated = false;
			inbound.setLength(0);
			synchronized (WecomLink.this) {
				tail = CompletableFuture.completedFuture(webSocket);
			}
			subscribeReq = UUID.randomUUID().toString();
			webSocket.request(1);
			send(WecomFrames.subscribe(botId, secret, subscribeReq));
		}

		@Override
		public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			if (!current()) {
				return null;
			}
			inbound.append(data);
			if (last) {
				String message = inbound.toString();
				inbound.setLength(0);
				incoming(message);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			if (webSocket != socket) {
				return null;
			}
			socket = null;
			authenticated = false;
			ScheduledFuture<?> pulse = heartbeat;
			if (pulse != null) {
				pulse.cancel(false);
			}
			scheduleReconnect();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			if (current()) {
				System.out.println("企业微信长连接中断");
			}
		}

	}

}
