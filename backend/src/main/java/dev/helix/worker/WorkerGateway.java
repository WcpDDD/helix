package dev.helix.worker;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;

public final class WorkerGateway {

	private WorkerGateway() {
	}

	public static void handle(HttpExchange exchange, WorkerSessions sessions) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		try {
			if ("/sessions".equals(path)) {
				if (!"POST".equals(method)) {
					write(exchange, 405, "");
					return;
				}
				String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				reply(exchange, sessions.accept(request));
				return;
			}
			String[] bits = path.split("/");
			if (bits.length < 3 || !"sessions".equals(bits[1])) {
				write(exchange, 404, "{\"detail\":\"没有这个接口。\"}");
				return;
			}
			int issue = Integer.parseInt(bits[2]);
			if (bits.length == 4 && "spec".equals(bits[3])) {
				if (!"GET".equals(method)) {
					write(exchange, 405, "");
					return;
				}
				reply(exchange, sessions.spec(issue));
				return;
			}
			long agent = agent(exchange);
			if (bits.length == 4 && "messages".equals(bits[3]) && "POST".equals(method)) {
				String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				reply(exchange, sessions.send(issue, agent, request));
				return;
			}
			if (bits.length == 4 && "conversations".equals(bits[3]) && "GET".equals(method)) {
				Long cursor;
				try {
					cursor = before(exchange);
				}
				catch (NumberFormatException exception) {
					write(exchange, 400, "{\"detail\":\"对话位置不对。\"}");
					return;
				}
				reply(exchange, sessions.list(issue, agent, cursor));
				return;
			}
			if (bits.length == 5 && "conversations".equals(bits[3]) && "GET".equals(method)) {
				reply(exchange, sessions.content(issue, agent, Long.parseLong(bits[4])));
				return;
			}
			if (bits.length == 6 && "conversations".equals(bits[3]) && "stream".equals(bits[5]) && "GET".equals(method)) {
				WorkerSessions.Result probe = sessions.content(issue, agent, Long.parseLong(bits[4]));
				if (probe.status() != 200) {
					reply(exchange, probe);
					return;
				}
				stream(exchange, sessions, issue, agent, Long.parseLong(bits[4]));
				return;
			}
			if (bits.length == 4 && "files".equals(bits[3]) && "GET".equals(method)) {
				reply(exchange, sessions.file(issue, agent, query(exchange, "path")));
				return;
			}
			write(exchange, 405, "");
		}
		catch (NumberFormatException exception) {
			write(exchange, 400, "{\"detail\":\"issue 编号不对。\"}");
		}
		catch (WorkspaceException exception) {
			write(exchange, 404, "{\"detail\":\"" + escape(exception.getMessage()) + "\"}");
		}
		catch (RuntimeException exception) {
			write(exchange, 503, "{\"detail\":\"对话没有读到。\"}");
		}
	}

	private static void stream(HttpExchange exchange, WorkerSessions sessions, int issue, long agent, long seq) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			sessions.stream(issue, agent, seq, out);
		}
	}

	private static long agent(HttpExchange exchange) {
		String value = query(exchange, "agent");
		if (value == null || value.isBlank()) {
			return 0;
		}
		long agentId = Long.parseLong(value);
		if (agentId < 0) {
			throw new NumberFormatException();
		}
		return agentId;
	}

	private static Long before(HttpExchange exchange) {
		String value = query(exchange, "before");
		if (value == null || value.isBlank()) {
			return null;
		}
		return Long.valueOf(value);
	}

	private static String query(HttpExchange exchange, String name) {
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw == null) {
			return null;
		}
		for (String pair : raw.split("&")) {
			int mark = pair.indexOf('=');
			String key = mark < 0 ? pair : pair.substring(0, mark);
			if (!name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
				continue;
			}
			String value = mark < 0 ? "" : pair.substring(mark + 1);
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		}
		return null;
	}

	private static void reply(HttpExchange exchange, WorkerSessions.Result result) throws IOException {
		byte[] body = result.body().getBytes(StandardCharsets.UTF_8);
		String type = result.body().isBlank() ? "text/plain; charset=utf-8" : "application/json; charset=utf-8";
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(result.status(), body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void write(HttpExchange exchange, int status, String body) throws IOException {
		reply(exchange, new WorkerSessions.Result(status, body));
	}

	private static String escape(String message) {
		String text = message == null || message.isBlank() ? "对话没有读到。" : message;
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
