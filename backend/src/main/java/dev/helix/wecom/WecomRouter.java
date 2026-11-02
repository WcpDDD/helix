package dev.helix.wecom;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class WecomRouter {

	private final Set<String> seen = new LinkedHashSet<>();

	private final Ask asks;

	WecomRouter(Ask asks) {
		this.asks = asks;
	}

	void accept(WecomFrames.Frame frame, Consumer<String> send) {
		if ("aibot_msg_callback".equals(frame.cmd())) {
			if (!fresh(frame.msgId())) {
				return;
			}
			if (!"text".equals(frame.msgType())) {
				send.accept(WecomFrames.stream(frame.reqId(), streamId(), "目前只接文字。", true));
				return;
			}
			String question = WecomFrames.question(frame.content());
			if (question.isEmpty()) {
				send.accept(WecomFrames.stream(frame.reqId(), streamId(), "说一下要查的事。", true));
				return;
			}
			asks.accept(frame.reqId(), frame.userId(), question);
			return;
		}
		if ("aibot_event_callback".equals(frame.cmd()) && "enter_chat".equals(frame.eventType()) && fresh(frame.msgId())) {
			send.accept(WecomFrames.welcome(frame.reqId(), "我是 Helix。直接说要查的 bug，或问项目里的事。"));
		}
	}

	private boolean fresh(String msgId) {
		if (msgId == null || msgId.isBlank()) {
			return true;
		}
		synchronized (seen) {
			if (!seen.add(msgId)) {
				return false;
			}
			if (seen.size() > 200) {
				Iterator<String> iterator = seen.iterator();
				iterator.next();
				iterator.remove();
			}
			return true;
		}
	}

	private static String streamId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	@FunctionalInterface
	interface Ask {

		void accept(String reqId, String userId, String question);

	}

}
