package dev.helix.board;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleasePlanFileTest {

	@Test
	void roundTripSortsIssuesAndKeepsEveryDate() {
		Map<Integer, LocalDate> dates = new LinkedHashMap<>();
		dates.put(21, LocalDate.parse("2026-11-18"));
		dates.put(9, LocalDate.parse("2026-11-03"));

		Map<Integer, LocalDate> read = ReleasePlanFile.parse(ReleasePlanFile.render(dates));

		assertThat(read).containsExactly(
				Map.entry(9, LocalDate.parse("2026-11-03")),
				Map.entry(21, LocalDate.parse("2026-11-18")));
	}

	@Test
	void blankFileIsAnEmptyPlan() {
		assertThat(ReleasePlanFile.parse("")).isEmpty();
		assertThat(ReleasePlanFile.parse("version: 1\n")).isEmpty();
	}

	@Test
	void aDuplicateIssueIsRejected() {
		assertThatThrownBy(() -> ReleasePlanFile.parse("""
				version: 1
				releases:
				  - issue: 21
				    date: "2026-11-03"
				  - issue: 21
				    date: "2026-11-04"
				""")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anUnquotedDateIsRejected() {
		assertThatThrownBy(() -> ReleasePlanFile.parse("""
				version: 1
				releases:
				  - issue: 21
				    date: 2026-11-03
				""")).isInstanceOf(IllegalArgumentException.class);
	}

}
