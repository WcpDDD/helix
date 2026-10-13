package dev.helix.work;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkPullSchedulerTest {

	@Test
	void pullsOnlyAtTheConfiguredMinuteAndOncePerSlot() {
		Set<java.time.LocalTime> times = WorkPullScheduler.timesOf("09:00,13:00,18:00");
		LocalDateTime morning = LocalDateTime.of(2026, 10, 13, 9, 0, 30);
		LocalDateTime later = LocalDateTime.of(2026, 10, 13, 9, 1, 0);
		LocalDateTime noon = LocalDateTime.of(2026, 10, 13, 13, 0, 1);

		assertThat(WorkPullScheduler.slot(morning, times)).isEqualTo(LocalDateTime.of(2026, 10, 13, 9, 0));
		assertThat(WorkPullScheduler.slot(morning, times)).isEqualTo(WorkPullScheduler.slot(morning.plusSeconds(20), times));
		assertThat(WorkPullScheduler.slot(later, times)).isNull();
		assertThat(WorkPullScheduler.slot(noon, times)).isEqualTo(LocalDateTime.of(2026, 10, 13, 13, 0));
	}

}
