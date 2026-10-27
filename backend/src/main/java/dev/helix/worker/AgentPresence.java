package dev.helix.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * worker 心跳带上来的正在运行的 agent。过期或下一拍没再报，就当成已经停了。
 */
@Component
public class AgentPresence {

	private final Duration lease;

	private final Clock clock;

	private final ConcurrentHashMap<String, ConcurrentHashMap<String, Run>> byWorker = new ConcurrentHashMap<>();

	@Autowired
	public AgentPresence(@Value("${helix.worker.lease:15s}") Duration lease) {
		this(lease, Clock.systemUTC());
	}

	AgentPresence(Duration lease, Clock clock) {
		this.lease = lease == null || lease.isZero() || lease.isNegative() ? Duration.ofSeconds(15) : lease;
		this.clock = clock;
	}

	public void replace(String workerId, List<Reported> runs) {
		if (workerId == null || workerId.isBlank() || runs == null) {
			return;
		}
		Instant until = clock.instant().plus(lease);
		ConcurrentHashMap<String, Run> next = new ConcurrentHashMap<>();
		for (Reported run : runs) {
			if (run == null || run.issue() <= 0) {
				continue;
			}
			next.put(run.issue() + ":" + run.agentId(), new Run(run.issue(), run.agentId(), until));
		}
		byWorker.put(workerId.strip(), next);
	}

	public Optional<Run> running(int issue) {
		Instant now = clock.instant();
		for (ConcurrentHashMap<String, Run> runs : byWorker.values()) {
			for (Run run : runs.values()) {
				if (run.issue() == issue && run.until().isAfter(now)) {
					return Optional.of(run);
				}
			}
		}
		return Optional.empty();
	}

	public record Reported(int issue, long agentId) {
	}

	public record Run(int issue, long agentId, Instant until) {
	}

}
