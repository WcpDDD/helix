package dev.helix.board;

import java.util.List;

import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabIssueClient.OpenedIssue;
import dev.helix.gitlab.GitlabIssueException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
public class AiManagedScan {

	private static final Logger log = LoggerFactory.getLogger(AiManagedScan.class);

	private final ProjectRepository projects;

	private final GitlabIssueClient gitlab;

	private final ManagerAnalysis managers;

	private final ScheduleLog journal;

	public AiManagedScan(ProjectRepository projects, GitlabIssueClient gitlab, ManagerAnalysis managers,
			ScheduleLog journal) {
		this.projects = projects;
		this.gitlab = gitlab;
		this.managers = managers;
		this.journal = journal;
	}

	public void run(String accessToken) {
		for (ProjectEntity project : projects.findAll()) {
			List<OpenedIssue> opened;
			try {
				opened = gitlab.opened(accessToken, project.getGitHost(), project.getGitOwner(), project.getGitRepo());
			}
			catch (GitlabIssueException exception) {
				log.warn("托管没有扫到 {}：{}", project.getName(), exception.status());
				journal.write("scan-failed", java.util.Map.of("project", project.getName(), "status", exception.status()));
				continue;
			}
			if (opened == null) {
				opened = List.of();
			}
			int managed = 0;
			for (OpenedIssue issue : opened) {
				if (issue.labels() == null || !issue.labels().contains(ProposalDraft.AI)) {
					continue;
				}
				managed++;
				log.info("托管 #{}，交给 manager", issue.iid());
				journal.write("handoff", java.util.Map.of("project", project.getName(), "issue", issue.iid()));
				managers.analyze(accessToken, project, issue.iid(), issue.labels());
			}
			journal.write("scan", java.util.Map.of("project", project.getName(), "opened", opened.size(), "managed", managed));
		}
	}

}
