package dev.helix.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class WorkerConversationClient {

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	public Exchange get(URI address, String path) {
		return exchange("GET", address, path, null, Duration.ofSeconds(10));
	}

	public Exchange post(URI address, String path, String body) {
		return exchange("POST", address, path, body, Duration.ofSeconds(30));
	}

	public void pipe(URI address, String path, OutputStream out) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(endpoint(address, path))
				.timeout(Duration.ofMinutes(15))
				.GET()
				.build();
		HttpResponse<InputStream> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("对话没有读到。");
		}
		if (response.statusCode() >= 400) {
			String detail = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
			throw new IOException(detail.isBlank() ? "对话没有读到。" : detail);
		}
		try (InputStream in = response.body()) {
			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				if (read == 0) {
					continue;
				}
				out.write(buffer, 0, read);
				out.flush();
			}
		}
	}

	private Exchange exchange(String method, URI address, String path, String body, Duration timeout) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(address, path)).timeout(timeout);
		if ("POST".equals(method)) {
			builder.header("Content-Type", "application/json");
			builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
		}
		else {
			builder.GET();
		}
		try {
			HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			return new Exchange(response.statusCode(), response.body());
		}
		catch (IOException exception) {
			return new Exchange(502, "");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new Exchange(502, "");
		}
	}

	private static URI endpoint(URI address, String path) {
		String text = address.toString();
		while (text.endsWith("/")) {
			text = text.substring(0, text.length() - 1);
		}
		return URI.create(text + path);
	}

	public record Exchange(int status, String body) {
	}

}
