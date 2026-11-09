package dev.helix.board;

import java.util.ArrayList;
import java.util.List;

import dev.helix.gitlab.GitlabIssueClient.IssueHead;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueListCacheTest {

	private final IssueListCache cache = new IssueListCache();

	@Test
	void aProjectStartsEmpty() {
		assertThat(cache.get(3L)).isNull();
	}

	@Test
	void storeIsWhatTheListReads() {
		int ticket = cache.begin(3L);
		cache.store(3L, ticket, List.of(head(21, "练习")));

		assertThat(cache.get(3L)).extracting(IssueHead::iid).containsExactly(21);
	}

	@Test
	void aStalePullDoesNotCoverAStatusEdit() {
		cache.store(3L, cache.begin(3L), List.of(head(21, "实现中")));
		int inflight = cache.begin(3L);

		cache.revise(3L, 21, "状态：审阅中", List.of("status/review"), false);
		cache.store(3L, inflight, List.of(head(21, "实现中")));

		assertThat(cache.get(3L)).singleElement().satisfies(head -> {
			assertThat(head.description()).isEqualTo("状态：审阅中");
			assertThat(head.labels()).containsExactly("status/review");
			assertThat(head.state()).isEqualTo("opened");
		});
	}

	@Test
	void closingKeepsTheEditedBody() {
		cache.store(3L, cache.begin(3L), List.of(head(21, "审阅中")));

		cache.revise(3L, 21, "状态：已上线", List.of("status/released"), true);

		IssueHead head = cache.get(3L).get(0);
		assertThat(head.state()).isEqualTo("closed");
		assertThat(head.description()).isEqualTo("状态：已上线");
	}

	@Test
	void aPullThatStartsAfterTheEditReplacesTheOverlay() {
		cache.store(3L, cache.begin(3L), List.of(head(21, "实现中")));
		cache.revise(3L, 21, "状态：审阅中", List.of("status/review"), false);
		int ticket = cache.begin(3L);

		cache.store(3L, ticket, List.of(head(21, "GitLab 上的审阅中")));

		assertThat(cache.get(3L)).singleElement().extracting(IssueHead::description).isEqualTo("GitLab 上的审阅中");
	}

	@Test
	void insertShowsUpBeforeTheNextPullAndThenYields() {
		cache.store(3L, cache.begin(3L), List.of(head(21, "已有")));

		cache.insert(3L, head(40, "新建"));

		assertThat(cache.get(3L)).extracting(IssueHead::iid).containsExactly(21, 40);

		int ticket = cache.begin(3L);
		cache.store(3L, ticket, List.of(head(40, "GitLab 上的新建"), head(21, "已有")));

		assertThat(cache.get(3L)).extracting(IssueHead::description).containsExactly("GitLab 上的新建", "已有");
	}

	@Test
	void insertBeforeTheFirstPullDoesNotPublishAPartialBoard() {
		cache.insert(3L, head(40, "新建"));

		assertThat(cache.get(3L)).isNull();

		int ticket = cache.begin(3L);
		cache.store(3L, ticket, List.of(head(40, "GitLab 上的新建"), head(21, "已有")));

		assertThat(cache.get(3L)).extracting(IssueHead::iid).containsExactly(40, 21);
	}

	@Test
	void unknownIssueIsLeftUntouched() {
		int ticket = cache.begin(3L);
		cache.store(3L, ticket, List.of(head(21, "练习")));
		int inflight = cache.begin(3L);

		cache.revise(3L, 99, "状态：审阅中", List.of("status/review"), false);
		cache.store(3L, inflight, List.of(head(21, "下一轮")));

		assertThat(cache.get(3L)).singleElement().extracting(IssueHead::description).isEqualTo("下一轮");
	}

	private static IssueHead head(int iid, String description) {
		return new IssueHead(iid, "标题 " + iid, description, "chenxinpei", "", "opened", new ArrayList<>());
	}

}
