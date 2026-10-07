package dev.helix.board;

import java.time.LocalDate;
import java.util.List;

public record TaskView(
		String code,
		String description,
		String owner,
		String status,
		String stage,
		LocalDate startedAt,
		LocalDate expectedEndAt,
		LocalDate finishedAt,
		IssueSummary issue,
		List<ChildIssueView> issues,
		List<EdgeView> edges) {

	public record IssueSummary(
			String url,
			String kind,
			String author,
			String allowedScope,
			String blastRadius,
			String review,
			String version,
			String conclusion,
			List<MergeRequestView> mergeRequests,
			List<EnvironmentView> environments) {
	}

	public record ChildIssueView(
			String id,
			String url,
			String title,
			String status,
			String stage,
			String owner,
			String kind,
			String author,
			String allowedScope,
			String blastRadius,
			String review,
			String version,
			String conclusion,
			List<MergeRequestView> mergeRequests,
			List<EnvironmentView> environments) {
	}

	public record EdgeView(String id, String from, String to, boolean needsContract, String contractRef) {
	}

	public record MergeRequestView(String iid, String title, String state, String url) {
	}

	public record EnvironmentView(String name, String url) {
	}

}
