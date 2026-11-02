package dev.helix.wecom;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WecomFramesTest {

	private final JsonMapper json = new JsonMapper();

	@Test
	void subscribeCarriesTheBotAndDoesNotUseTheWebhookKeys() {
		JsonNode frame = json.readTree(WecomFrames.subscribe("bot-1", "secret-1", "req-1"));

		assertThat(frame.path("cmd").asText()).isEqualTo("aibot_subscribe");
		assertThat(frame.path("headers").path("req_id").asText()).isEqualTo("req-1");
		assertThat(frame.path("body").path("bot_id").asText()).isEqualTo("bot-1");
		assertThat(frame.path("body").path("secret").asText()).isEqualTo("secret-1");
		assertThat(frame.toString()).doesNotContain("EncodingAESKey");
	}

	@Test
	void streamReplyReusesTheCallbackReqId() {
		JsonNode frame = json.readTree(WecomFrames.stream("callback-1", "stream-1", "根因在这里", true));

		assertThat(frame.path("cmd").asText()).isEqualTo("aibot_respond_msg");
		assertThat(frame.path("headers").path("req_id").asText()).isEqualTo("callback-1");
		assertThat(frame.path("body").path("stream").path("id").asText()).isEqualTo("stream-1");
		assertThat(frame.path("body").path("stream").path("finish").asBoolean()).isTrue();
		assertThat(frame.path("body").path("stream").path("content").asText()).isEqualTo("根因在这里");
	}

	@Test
	void readsATextCallbackAndStripsTheMention() {
		String raw = """
				{"cmd":"aibot_msg_callback","headers":{"req_id":"r1"},"body":{"msgid":"m1","from":{"userid":"chenxinpei"},"msgtype":"text","text":{"content":"@Helix 看一下这个报错"}}}
				""";

		WecomFrames.Frame frame = WecomFrames.read(raw);

		assertThat(frame.cmd()).isEqualTo("aibot_msg_callback");
		assertThat(frame.reqId()).isEqualTo("r1");
		assertThat(frame.msgId()).isEqualTo("m1");
		assertThat(frame.userId()).isEqualTo("chenxinpei");
		assertThat(WecomFrames.question(frame.content())).isEqualTo("看一下这个报错");
	}

	@Test
	void routerAnswersTextOnceAndWelcomesOnEnter() {
		List<String> asked = new ArrayList<>();
		List<String> sent = new ArrayList<>();
		WecomRouter router = new WecomRouter((reqId, userId, question) -> asked.add(reqId + "\n" + userId + "\n" + question));
		WecomFrames.Frame text = WecomFrames.read("""
				{"cmd":"aibot_msg_callback","headers":{"req_id":"r1"},"body":{"msgid":"m1","from":{"userid":"zhangsan"},"msgtype":"text","text":{"content":"这个接口 500"}}}
				""");
		WecomFrames.Frame again = WecomFrames.read("""
				{"cmd":"aibot_msg_callback","headers":{"req_id":"r1"},"body":{"msgid":"m1","from":{"userid":"zhangsan"},"msgtype":"text","text":{"content":"这个接口 500"}}}
				""");
		WecomFrames.Frame enter = WecomFrames.read("""
				{"cmd":"aibot_event_callback","headers":{"req_id":"r2"},"body":{"msgid":"m2","from":{"userid":"zhangsan"},"msgtype":"event","event":{"eventtype":"enter_chat"}}}
				""");

		router.accept(text, sent::add);
		router.accept(again, sent::add);
		router.accept(enter, sent::add);

		assertThat(asked).containsExactly("r1\nzhangsan\n这个接口 500");
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).contains("aibot_respond_welcome_msg");
	}

	@Test
	void callerUsesTheInternalNameWhenTheWecomIdMatches() {
		assertThat(WecomIdentity.caller("chenxinpei", "chenxinpei", null)).isEqualTo("chenxinpei");
		assertThat(WecomIdentity.caller("ldap-uid", null, "gitlab-name")).isEqualTo("gitlab-name");
		assertThat(WecomIdentity.caller("woABCDEF", null, null)).isEqualTo("woABCDEF");
		assertThat(WecomIdentity.externalId("woABCDEF")).isNegative();
	}

}
