package dev.helix.board;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BoardSeed implements ApplicationRunner {

	private static final String UNSCOPED = "GitLab issue 没有写允许变更范围。";

	private static final String UNSTATED_BLAST = "GitLab issue 没有写爆炸半径。";

	private final ObjectMapper json;
	private final ProjectRepository projects;
	private final MemberRepository members;
	private final TaskRepository tasks;
	private final IssueRepository issues;
	private final IssueMergeRequestRepository mergeRequests;
	private final IssueEdgeRepository edges;

	public BoardSeed(ObjectMapper json, ProjectRepository projects, MemberRepository members, TaskRepository tasks,
			IssueRepository issues, IssueMergeRequestRepository mergeRequests, IssueEdgeRepository edges) {
		this.json = json;
		this.projects = projects;
		this.members = members;
		this.tasks = tasks;
		this.issues = issues;
		this.mergeRequests = mergeRequests;
		this.edges = edges;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws IOException {
		if (tasks.count() > 0) {
			return;
		}
		try (InputStream input = new ClassPathResource("board/qingflow-issues.json").getInputStream()) {
			load(json.readValue(input, BoardCatalog.class));
		}
	}

	private void load(BoardCatalog catalog) {
		BoardCatalog.ProjectInfo source = catalog.project();
		ProjectEntity project = projects.save(
				new ProjectEntity(source.name(), source.gitHost(), source.gitOwner(), source.gitRepo()));
		Map<String, MemberEntity> people = new HashMap<>();
		for (BoardCatalog.MemberInfo member : catalog.members()) {
			people.put(member.login(), members.save(new MemberEntity(project, member.name(), member.login())));
		}
		Map<Integer, List<BoardCatalog.IssueInfo>> children = new HashMap<>();
		List<BoardCatalog.IssueInfo> mains = new ArrayList<>();
		for (BoardCatalog.IssueInfo issue : catalog.issues()) {
			if (issue.rootIid() == null) {
				mains.add(issue);
			}
			else {
				children.computeIfAbsent(issue.rootIid(), ignored -> new ArrayList<>()).add(issue);
			}
		}
		mains.sort(Comparator.comparingInt(BoardCatalog.IssueInfo::iid));
		Map<Integer, IssueEntity> saved = new HashMap<>();
		Map<Integer, TaskEntity> taskByRoot = new HashMap<>();
		for (BoardCatalog.IssueInfo main : mains) {
			List<BoardCatalog.IssueInfo> kids = children.getOrDefault(main.iid(), List.of());
			TaskEntity task = tasks.save(new TaskEntity(project, main.status(), main.stage(), main.createdOn(),
					main.dueOn(), main.finishedOn()));
			taskByRoot.put(main.iid(), task);
			saved.put(main.iid(), saveIssue(project, task, true, main, people));
			for (BoardCatalog.IssueInfo child : kids) {
				saved.put(child.iid(), saveIssue(project, task, false, child, people));
			}
		}
		for (BoardCatalog.EdgeInfo edge : catalog.edges()) {
			IssueEntity from = saved.get(edge.from());
			IssueEntity to = saved.get(edge.to());
			if (from == null || to == null || from.isMainIssue() || to.isMainIssue()) {
				continue;
			}
			edges.save(new IssueEdgeEntity(taskByRoot.get(rootOf(edge.from(), catalog)), from, to, edge.needsContract(),
					edge.contractRef()));
		}
	}

	private IssueEntity saveIssue(ProjectEntity project, TaskEntity task, boolean main, BoardCatalog.IssueInfo info,
			Map<String, MemberEntity> people) {
		Instant createdAt = info.createdOn().atStartOfDay().toInstant(ZoneOffset.UTC);
		IssueEntity issue = issues.save(new IssueEntity(project, task, main, info.iid(), info.title(), info.kind(),
				info.stage(), info.status(), people.get(info.author()), people.get(info.owner()), UNSCOPED,
				UNSTATED_BLAST, info.conclusion(), info.review(), null, null, createdAt));
		for (BoardCatalog.MergeInfo request : info.mergeRequests()) {
			mergeRequests.save(new IssueMergeRequestEntity(issue, request.iid(), request.title(), request.state(),
					request.url()));
		}
		return issue;
	}

	private static int rootOf(int iid, BoardCatalog catalog) {
		for (BoardCatalog.IssueInfo issue : catalog.issues()) {
			if (issue.iid() == iid) {
				return issue.rootIid() == null ? iid : issue.rootIid();
			}
		}
		return iid;
	}

}
