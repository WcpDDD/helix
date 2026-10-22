package dev.helix.board;

import java.util.List;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.OpenedIssue;
import dev.helix.gitlab.GitlabIssueException;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiManagedScanTest {

	@Test
	void onlyManagedIssuesReachTheManager() {
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		ManagerAnalysis managers = mock(ManagerAnalysis.class);
		ProjectEntity project = new ProjectEntity("轻流", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace");
		when(projects.findAll()).thenReturn(List.of(project));
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace")).thenReturn(List.of(
				new OpenedIssue(21, List.of("status/proposal", "ai/managed")),
				new OpenedIssue(22, List.of("status/proposal", "type/bug"))));

		new AiManagedScan(projects, gitlab, managers, journal("scan-one")).run("tok");

		verify(managers).analyze("tok", project, 21, List.of("status/proposal", "ai/managed"));
		verify(managers, never()).analyze("tok", project, 22, List.of("status/proposal", "type/bug"));
	}

	@Test
	void oneProjectFailureDoesNotStopTheNext() {
		ProjectRepository projects = mock(ProjectRepository.class);
		GitlabIssueClient gitlab = mock(GitlabIssueClient.class);
		ManagerAnalysis managers = mock(ManagerAnalysis.class);
		ProjectEntity broken = new ProjectEntity("坏的", "hackers.oalite.com", "qingflow-develop", "broken");
		ProjectEntity healthy = new ProjectEntity("好的", "hackers.oalite.com", "qingflow-develop", "healthy");
		when(projects.findAll()).thenReturn(List.of(broken, healthy));
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "broken"))
				.thenThrow(new GitlabIssueException(502));
		when(gitlab.opened("tok", "hackers.oalite.com", "qingflow-develop", "healthy"))
				.thenReturn(List.of(new OpenedIssue(9, List.of("ai/managed"))));

		new AiManagedScan(projects, gitlab, managers, journal("scan-next")).run("tok");

		verify(managers).analyze("tok", healthy, 9, List.of("ai/managed"));
		verify(managers, never()).analyze("tok", broken, 9, List.of("ai/managed"));
	}

	private static ScheduleLog journal(String name) {
		return new ScheduleLog(java.nio.file.Path.of("target", name), ScheduleLog.MAX_BYTES);
	}

}
