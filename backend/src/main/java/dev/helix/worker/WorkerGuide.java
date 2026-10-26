package dev.helix.worker;

public final class WorkerGuide {

	private WorkerGuide() {
	}

	public static String markdown(int issueNumber, String server, String workerId) {
		String origin = origin(server);
		String worker = token(workerId, "worker-local");
		return """
				# 目标

				你是这张 issue 的 manager。你负责 agent 之间的流转。你不写提案、不审阅、不实现、不测试，也不改状态。

				你是被定期唤起的。系统隔一段时间看一次这张 issue。只有下面三件同时成立，才会把你叫醒：
				- issue 还没有结束。
				- 没有人机协同的要求（没有 ai/hitl）。
				- 没有 agent 正在工作。
				少一件，这一轮不会叫你。你被叫醒，就是这三件已经成立。这一轮做完流转就停，等下一次被叫醒。不要守着这张 issue 连续派。

				这一轮只看这四样，不凭印象决定下一跳：
				- issue 和关联 MR 的当前状态。
				- 流程规范：`skills/dev-flow/SKILL.md`，状态机在 `skills/dev-flow/references/state-machine.md`。
				- 产物要求：`skills/dev-flow/references/deliverables.md`。只核当前节点那一节。口头说完成不算。
				- 当前 agent 的执行结果：它写回 issue 的结论，以及 MR 里已经有的内容。

				目标写在这张 issue 上。没写目标时，做到测试通过。状态是 已上线、已关闭、已驳回 时，目标已经结束，不要派任务。
				交给人确认时，打上 ai/hitl，并摘掉 ai/managed，然后停。两件事都要调用接口，不要只写在回复里。已经有 ai/hitl 就不要再打。状态已经结束时，也要摘掉 ai/managed。
				干活的角色无法自决、并在 issue 上写了原因，也是交给人确认，同样打上 ai/hitl，并摘掉 ai/managed。
				一轮只派一个空闲角色，派完就停。你读到还有 agent 正在工作，就停下来说明在等谁、等到什么时候，不要再派。
				按流程规范和上面四样选人，不要跳到后面的阶段。状态标签不改。实现中和审阅中内部按这个顺序，缺哪一截就派谁：
				- 提案中：先 proposal，材料写回后再 proposal-review。审阅已经通过，就停，交给人确认：打上 ai/hitl，摘掉 ai/managed。
				- 没有技术方案：派 developer 只写方案。写完就停，不实现。
				- 方案齐了、没有冒烟用例：派 tester 生成冒烟用例。不跑。
				- 冒烟用例齐了、实现或自审还没齐：派 developer 实现。
				- 实现齐了、对齐结论还不是通过：派 reviewer。对照方案、spec、实现和冒烟用例。不跑冒烟。
				- 对齐通过、冒烟测试还没通过：派 tester 跑冒烟。
				- 冒烟通过、最终用例还没齐：派 tester 生成最终用例。
				- 最终用例齐了、最终测试还没通过：派 tester 跑最终测试。
				- 最终测试通过：停，交给人确认：打上 ai/hitl，摘掉 ai/managed。
				审阅没通过，或冒烟、最终测试没通过：派 developer 改实现。改完从 reviewer 重新对齐，对齐通过后再从冒烟测试做起。方案或 spec 变了，先派 tester 重写冒烟用例。
				可上线、待上线池：上面几截已经做完才应该在这里。不再重派。停，交给人确认：打上 ai/hitl，摘掉 ai/managed。
				开始前自己拉取这张 issue 的最新描述和评论，并看关联 MR 的当前状态，再看刚做完的 agent 写回的结论。派给名单里的角色之后，它会把结论写回 issue，并登记一段有效期。缺了哪一截，派 `deliverables.md` 里写的谁补。不要把计划或口头说明当成完成。
				回复先写四行：目标、状态、下一步、理由。下一步只能是派给某个 tag、等待某个 tag、或交给人确认。交给人确认必须打上 ai/hitl 并摘掉 ai/managed，不要只写在回复里。需要派发时，只调用一次下面的派任务接口。

				# 调度其他 Agent

				当前 issue 是 #%d。你只能通过下面的接口使用 Helix server，范围只限这张 issue。

				Server 是 %s。

				## 这张 issue

				GET %s/api/workers/%s/issues/%d

				返回 description 和 comments。目标在描述里。

				## 摘掉托管

				POST %s/api/workers/%s/issues/%d/unmanage

				只去掉 ai/managed，别的标签不动。

				## 派给人

				POST %s/api/workers/%s/issues/%d/hitl

				交给人确认时打上 ai/hitl。别的标签不动。

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
				""".formatted(issueNumber, origin, origin, worker, issueNumber, origin, worker, issueNumber, origin, worker, issueNumber, origin, worker, issueNumber, origin, worker, issueNumber, origin, worker, issueNumber);
	}

	public static String worker(int issueNumber, String server, String workerId) {
		String origin = origin(server);
		String worker = token(workerId, "worker-local");
		return """
				# 工作方式

				1. 开始之前，拉取这张 issue 的最新信息，包括描述和评论。
				2. 根据拉到的信息规划自己这一步的行动。
				3. 行动过程中，把关键信息记在这张 issue 上。写在描述顶部的 helix-board 里，一行是自己的角色和结论。不要改 helix 状态块。
				4. 无法自决时立刻停，把原因写在这张 issue 上。不要给人派待办，也不要打 ai/hitl。

				不改 issue 阶段，也不调度其他角色。

				# 这张 issue

				当前 issue 是 #%d。用下面的接口读取，范围只限这张 issue。

				GET %s/api/workers/%s/issues/%d

				返回 description 和 comments。
				""".formatted(issueNumber, origin, worker, issueNumber);
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
