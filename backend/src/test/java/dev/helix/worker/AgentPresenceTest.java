package dev.helix.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPresenceTest {

	@Test
	void aLaterHeartbeatDropsAnAgentThatFinished() {
		AgentPresence presence = new AgentPresence(Duration.ofSeconds(15));
		presence.replace("worker-local", List.of(new AgentPresence.Reported(235, 2L)));

		assertThat(presence.running(235)).isPresent();

		presence.replace("worker-local", List.of());

		assertThat(presence.running(235)).isEmpty();
	}

	@Test
	void aRunExpiresWhenTheWorkerStopsReporting() {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-26T12:00:00Z"));
		Clock clock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneId.of("UTC");
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		AgentPresence presence = new AgentPresence(Duration.ofSeconds(15), clock);
		presence.replace("worker-local", List.of(new AgentPresence.Reported(235, 3L)));

		now.set(now.get().plusSeconds(14));
		assertThat(presence.running(235)).isPresent();

		now.set(now.get().plusSeconds(2));
		assertThat(presence.running(235)).isEmpty();
	}

}
