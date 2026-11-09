package dev.helix.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Streamable HTTP 上的 MCP JSON-RPC。没有 id 的通知不回包。
 */
final class McpExchange {

	private static final JsonMapper JSON = new JsonMapper();

	private McpExchange() {
	}

	@FunctionalInterface
	interface Submit {

		String submit(String kind, String title, Map<String, String> fields);

	}

	static JsonNode handle(JsonNode request, Submit submit) {
		if (request == null || !request.isObject()) {
			return error(null, -32600, "请求不是 JSON 对象。");
		}
		JsonNode id = request.get("id");
		boolean notification = id == null || id.isNull();
		String method = text(request.get("method"));
		if (method.isEmpty()) {
			return error(id, -32600, "没有 method。");
		}
		if (method.startsWith("notifications/")) {
			return null;
		}
		if (notification) {
			return error(null, -32600, "请求没有 id。");
		}
		return switch (method) {
			case "initialize" -> result(id, initialize());
			case "ping" -> result(id, JSON.createObjectNode());
			case "tools/list" -> result(id, McpCatalog.tools());
			case "tools/call" -> result(id, call(request.get("params"), submit));
			default -> error(id, -32601, "没有这个方法。");
		};
	}

	private static ObjectNode initialize() {
		ObjectNode body = JSON.createObjectNode();
		body.put("protocolVersion", "2025-03-26");
		ObjectNode capabilities = body.putObject("capabilities");
		capabilities.putObject("tools");
		ObjectNode info = body.putObject("serverInfo");
		info.put("name", "helix");
		info.put("title", "Helix");
		info.put("version", "0.0.1");
		body.put("instructions", McpCatalog.instructions());
		return body;
	}

	private static ObjectNode call(JsonNode params, Submit submit) {
		String name = params == null ? "" : text(params.get("name"));
		if (!McpCatalog.TOOL.equals(name)) {
			return toolError("没有这个工具。用 submit_task 提交需求或缺陷。");
		}
		JsonNode arguments = params.get("arguments");
		if (arguments == null || !arguments.isObject()) {
			return toolError("arguments 要是对象，里面有 kind、title、fields。");
		}
		String kind = text(arguments.get("kind"));
		String title = text(arguments.get("title"));
		Map<String, String> fields = fields(arguments.get("fields"));
		if (fields == null) {
			return toolError("fields 要是对象。需求 feature 填：" + String.join("、", ProposalFields.feature())
					+ "。缺陷 bug 填：" + String.join("、", ProposalFields.bug()) + "。");
		}
		try {
			return toolText(submit.submit(kind, title, fields), false);
		}
		catch (RuntimeException exception) {
			return toolText(failure(exception), true);
		}
	}

	private static Map<String, String> fields(JsonNode node) {
		if (node == null || node.isNull()) {
			return Map.of();
		}
		if (!node.isObject()) {
			return null;
		}
		Map<String, String> fields = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode value = entry.getValue();
			fields.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText(""));
		}
		return fields;
	}

	private static ObjectNode toolText(String text, boolean failed) {
		ObjectNode body = JSON.createObjectNode();
		ArrayNode content = body.putArray("content");
		ObjectNode part = content.addObject();
		part.put("type", "text");
		part.put("text", text);
		if (failed) {
			body.put("isError", true);
		}
		return body;
	}

	private static String failure(RuntimeException exception) {
		if (exception instanceof ResponseStatusException status) {
			String reason = status.getReason();
			if (reason != null && !reason.isBlank()) {
				return reason;
			}
			int code = status.getStatusCode().value();
			if (code == 401 || code == 403) {
				return "GitLab 身份无效。";
			}
		}
		if (exception instanceof IllegalArgumentException && exception.getMessage() != null && !exception.getMessage().isBlank()) {
			return exception.getMessage();
		}
		return "任务没有建起来。";
	}

	private static ObjectNode toolError(String text) {
		return toolText(text, true);
	}

	private static ObjectNode result(JsonNode id, JsonNode value) {
		ObjectNode body = JSON.createObjectNode();
		body.put("jsonrpc", "2.0");
		body.set("id", id);
		body.set("result", value);
		return body;
	}

	private static ObjectNode error(JsonNode id, int code, String message) {
		ObjectNode body = JSON.createObjectNode();
		body.put("jsonrpc", "2.0");
		if (id == null || id.isNull()) {
			body.putNull("id");
		}
		else {
			body.set("id", id);
		}
		ObjectNode error = body.putObject("error");
		error.put("code", code);
		error.put("message", message);
		return body;
	}

	private static String text(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		return node.asText("").strip();
	}

}
