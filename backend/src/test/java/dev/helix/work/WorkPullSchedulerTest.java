package dev.helix.work;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import dev.helix.auth.GitlabSessionCredential;
import dev.helix.board.ProjectEntity;
import dev.helix.board.ProjectRepository;
import dev.helix.config.HelixProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

	@Test
	void heartbeatPullsOpenedWorkspacesBetweenTheClockTimes() {
		ProjectWorkspaces workspaces = mock(ProjectWorkspaces.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabSessionCredential credential = new GitlabSessionCredential();
		credential.remember("tok");
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(projects.findAll()).thenReturn(List.of(project));
		when(workspaces.opened(project)).thenReturn(Path.of("work"));
		HelixProperties properties = new HelixProperties(null, null, null, null, "09:00,13:00,18:00", 2, null);
		WorkPullScheduler scheduler = new WorkPullScheduler(workspaces, projects, credential, properties, false);
		LocalDateTime first = LocalDateTime.of(2026, 10, 22, 10, 30);
		try {
			scheduler.follow(first);
			scheduler.follow(first.plusSeconds(40));
			scheduler.follow(first.plusSeconds(WorkPullScheduler.FOLLOW_SECONDS));
			verify(workspaces, times(2)).requestPull(project, "tok");
		}
		finally {
			scheduler.close();
		}
	}

}
