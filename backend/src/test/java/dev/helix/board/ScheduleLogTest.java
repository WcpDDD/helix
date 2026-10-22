package dev.helix.board;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleLogTest {

	@Test
	void rollsWhenTheFileReachesTheCap(@TempDir Path directory) {
		ScheduleLog journal = new ScheduleLog(directory, 80);
		journal.write("scan", java.util.Map.of("project", "轻流", "opened", 2, "managed", 1));
		journal.write("handoff", java.util.Map.of("project", "轻流", "issue", 21));
		journal.write("scan", java.util.Map.of("project", "轻流", "opened", 2, "managed", 0));

		assertThat(journal.files()).hasSizeGreaterThan(1);
		assertThat(journal.files().getFirst().current()).isTrue();
		assertThat(journal.read("schedule.jsonl", 10).records()).isNotEmpty();
		assertThat(journal.read("../schedule.jsonl", 10)).isNull();
	}

	@Test
	void keepsTheNewestLines(@TempDir Path directory) throws Exception {
		ScheduleLog journal = new ScheduleLog(directory, ScheduleLog.MAX_BYTES);
		journal.write("idle", java.util.Map.of("reason", "没有登录"));
		journal.write("handoff", java.util.Map.of("issue", 9));

		assertThat(journal.read("schedule.jsonl", 1).records()).extracting(row -> row.get("event")).containsExactly("handoff");
		assertThat(Files.size(directory.resolve("schedule.jsonl"))).isPositive();
	}

}
