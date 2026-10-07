package dev.helix.board;

import java.time.LocalDate;
import java.util.List;

public record BoardCatalog(
		ProjectInfo project,
		List<MemberInfo> members,
		List<IssueInfo> issues,
		List<EdgeInfo> edges) {

	public record ProjectInfo(String name, String gitHost, String gitOwner, String gitRepo) {
	}

	public record MemberInfo(String login, String name) {
	}

	public record IssueInfo(
			int iid,
			String title,
			String status,
			String stage,
			String kind,
			String author,
			String owner,
			LocalDate createdOn,
			LocalDate dueOn,
			LocalDate finishedOn,
			Integer rootIid,
			String review,
			String conclusion,
			List<MergeInfo> mergeRequests) {
	}

	public record MergeInfo(String iid, String title, String state, String url) {
	}

	public record EdgeInfo(int from, int to, boolean needsContract, String contractRef) {
	}

}
