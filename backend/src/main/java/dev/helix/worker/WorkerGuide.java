package dev.helix.worker;

public final class WorkerGuide {

	private WorkerGuide() {
	}

	public static String markdown(int issueNumber, String server, String workerId) {
		String origin = origin(server);
		String worker = token(workerId, "worker-local");
		return """
				# 调度其他 Agent

				当前 issue 是 #%d。你只能通过下面三个接口使用 Helix server，范围只限这张 issue。

				Server 是 %s。

				## 派任务

				给这张 issue 上的一个 Agent 派一件事：

				POST %s/api/workers/%s/issues/%d/agents/{agent}/tasks
				Content-Type: application/json

				{"text":"要它做的事"}

				路径里的 agent 用角色名：manager、arch、proposal、proposal-review、developer、reviewer、tester。也可以用这个项目里的 agent id。

				## 对话目录

				读取这张 issue 上某个 Agent 已经有的对话目录：

				GET %s/api/workers/%s/issues/%d/agents/{agent}/conversations

				目录里是 seq、role、status、createdAt、preview。preview 只是开头一小段。hasMore 为 true 时，用 before=最早一条的 seq 再取更早的一页。

				## 一条完整对话

				用目录里的 seq 调阅这一条的全文：

				GET %s/api/workers/%s/issues/%d/agents/{agent}/conversations/{seq}

				返回 role、status、createdAt 和 parts。parts 里是正文、思考和工具调用。

				不要改 issue 阶段，不要合并变更，也不要调用这些之外的接口。
				""".formatted(issueNumber, origin, origin, worker, issueNumber, origin, worker, issueNumber, origin, worker, issueNumber);
	}

	private static String origin(String server) {
		String value = token(server, "http://127.0.0.1:8080");
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static String token(String value, String fallback) {
		if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r") || value.contains(" ")) {
			return fallback;
		}
		return value.strip();
	}

}
