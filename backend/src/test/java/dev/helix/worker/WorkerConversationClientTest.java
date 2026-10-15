package dev.helix.worker;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerConversationClientTest {

	@Test
	void pipeFlushesBeforeTheStreamEnds() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch seen = new CountDownLatch(1);
		ByteArrayOutputStream received = new ByteArrayOutputStream();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/sessions/7/conversations/2/stream", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write("event: text\ndata: {\"text\":\"甲\"}\n\n".getBytes(StandardCharsets.UTF_8));
				out.flush();
				try {
					release.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				out.write("event: done\ndata: {\"status\":\"done\"}\n\n".getBytes(StandardCharsets.UTF_8));
			}
		});
		server.start();
		AtomicReference<Exception> failure = new AtomicReference<>();
		Thread copier = new Thread(() -> {
			try {
				new WorkerConversationClient().pipe(
						URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
						"/sessions/7/conversations/2/stream",
						new OutputStream() {
							@Override
							public void write(int value) {
								received.write(value);
							}

							@Override
							public void flush() {
								if (received.toString(StandardCharsets.UTF_8).contains("甲")) {
									seen.countDown();
								}
							}
						});
			}
			catch (Exception exception) {
				failure.set(exception);
				seen.countDown();
			}
		});
		try {
			copier.start();
			assertThat(seen.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();
			assertThat(received.toString(StandardCharsets.UTF_8)).contains("甲").doesNotContain("event: done");
		}
		finally {
			release.countDown();
			copier.join(5000);
			server.stop(0);
		}
	}

}
