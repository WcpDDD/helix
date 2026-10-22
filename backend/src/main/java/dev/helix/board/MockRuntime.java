package dev.helix.board;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * mock agent 在 server 上的运行状态。一次派发占住一个有效期，过期后才算空闲。
 */
@Component
public class MockRuntime {

	public static final Duration LEASE = Duration.ofMinutes(2);

	private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(SHANGHAI);

	private static final List<String> PATH = List.of("提案中", "实现中", "审阅中", "可上线", "待上线池", "已上线");

	private final Clock clock;

	private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

	public MockRuntime() {
		this(Clock.systemUTC());
	}

	MockRuntime(Clock clock) {
		this.clock = clock;
	}

	public Hold hold(long projectId, int iid, AgentEntity agent) {
		String tag = agent.getTag() == null ? agent.getName() : agent.getTag();
		Instant now = clock.instant();
		Lease created = new Lease(tag, agent.getId() == null ? 0L : agent.getId(), now.plus(LEASE));
		Lease kept = leases.compute(key(projectId, iid, tag),
				(ignored, current) -> current != null && current.until().isAfter(now) ? current : created);
		return new Hold(kept, kept == created);
	}

	public void release(long projectId, int iid, Lease lease) {
		if (lease != null) {
			leases.remove(key(projectId, iid, lease.tag()), lease);
		}
	}

	public Optional<Lease> running(long projectId, int iid) {
		Instant now = clock.instant();
		String prefix = key(projectId, iid, "");
		return leases.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith(prefix))
				.map(Map.Entry::getValue)
				.filter(lease -> lease.until().isAfter(now))
				.findFirst();
	}

	public Optional<Lease> active(long projectId, int iid, String tag) {
		Lease lease = leases.get(key(projectId, iid, tag));
		if (lease == null || !lease.until().isAfter(clock.instant())) {
			return Optional.empty();
		}
		return Optional.of(lease);
	}

	public String describe(long projectId, int iid, List<AgentEntity> agents) {
		StringBuilder text = new StringBuilder();
		if (agents != null) {
			for (AgentEntity agent : agents) {
				if (agent.getTag() != null && "manager".equals(agent.getTag())) {
					continue;
				}
				String tag = agent.getTag() == null ? agent.getName() : agent.getTag();
				text.append("- ").append(tag);
				if (agent.getId() != null) {
					text.append("（id ").append(agent.getId()).append('）');
				}
				Optional<Lease> lease = active(projectId, iid, tag);
				if (lease.isPresent()) {
					text.append("：运行中，到 ").append(format(lease.get().until())).append('\n');
				}
				else {
					text.append("：空闲\n");
				}
			}
		}
		if (text.isEmpty()) {
			text.append("（没有）\n");
		}
		return text.toString();
	}

	public static String nextStatus(String status) {
		String current = status == null || status.isBlank() ? "提案中" : status;
		int index = PATH.indexOf(current);
		if (index < 0 || index + 1 >= PATH.size()) {
			return null;
		}
		return PATH.get(index + 1);
	}

	public static String format(Instant instant) {
		return TIME.format(instant);
	}

	private static String key(long projectId, int iid, String tag) {
		return projectId + ":" + iid + ":" + tag;
	}

	public record Lease(String tag, long agentId, Instant until) {
	}

	public record Hold(Lease lease, boolean started) {
	}

}
