package dev.helix.wecom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class WecomFrames {

	static final String ENDPOINT = "wss://openws.work.weixin.qq.com";

	private static final JsonMapper JSON = new JsonMapper();

	private WecomFrames() {
	}

	static String subscribe(String botId, String secret, String reqId) {
		ObjectNode root = JSON.createObjectNode();
		root.put("cmd", "aibot_subscribe");
		root.putObject("headers").put("req_id", reqId);
		ObjectNode body = root.putObject("body");
		body.put("bot_id", botId);
		body.put("secret", secret);
		return JSON.writeValueAsString(root);
	}

	static String ping(String reqId) {
		ObjectNode root = JSON.createObjectNode();
		root.put("cmd", "ping");
		root.putObject("headers").put("req_id", reqId);
		return JSON.writeValueAsString(root);
	}

	static String welcome(String reqId, String text) {
		ObjectNode root = JSON.createObjectNode();
		root.put("cmd", "aibot_respond_welcome_msg");
		root.putObject("headers").put("req_id", reqId);
		ObjectNode body = root.putObject("body");
		body.put("msgtype", "text");
		body.putObject("text").put("content", text);
		return JSON.writeValueAsString(root);
	}

	static String stream(String reqId, String streamId, String content, boolean finish) {
		ObjectNode root = JSON.createObjectNode();
		root.put("cmd", "aibot_respond_msg");
		root.putObject("headers").put("req_id", reqId);
		ObjectNode stream = root.putObject("body").put("msgtype", "stream").putObject("stream");
		stream.put("id", streamId);
		stream.put("finish", finish);
		stream.put("content", content == null ? "" : content);
		return JSON.writeValueAsString(root);
	}

	static Frame read(String text) {
		try {
			JsonNode root = JSON.readTree(text);
			JsonNode body = root.path("body");
			Integer errcode = root.has("errcode") ? root.path("errcode").asInt() : null;
			return new Frame(root.path("cmd").asText(""), root.path("headers").path("req_id").asText(""), errcode,
					root.path("errmsg").asText(""), body.path("msgid").asText(""), body.path("msgtype").asText(""),
					body.path("event").path("eventtype").asText(""), body.path("from").path("userid").asText(""),
					body.path("text").path("content").asText(""));
		}
		catch (RuntimeException exception) {
			return new Frame("", "", null, "", "", "", "", "", "");
		}
	}

	static String question(String content) {
		String text = content == null ? "" : content.strip();
		if (text.startsWith("@")) {
			int space = text.indexOf(' ');
			int newline = text.indexOf('\n');
			int cut = space < 0 ? newline : newline < 0 ? space : Math.min(space, newline);
			if (cut > 0) {
				text = text.substring(cut + 1).strip();
			}
		}
		return text;
	}

	record Frame(String cmd, String reqId, Integer errcode, String errmsg, String msgId, String msgType, String eventType,
			String userId, String content) {
	}

}
