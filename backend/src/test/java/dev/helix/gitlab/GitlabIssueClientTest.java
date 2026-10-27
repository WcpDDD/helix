package dev.helix.gitlab;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class GitlabIssueClientTest {

	@Test
	void addLabelAsksGitlabToAttachThatLabel() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		server.expect(requestTo(
				"https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(header("Authorization", "Bearer tok"))
				.andExpect(content().json("{\"add_labels\":\"ai/hitl\"}"))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		client.addLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 21, "ai/hitl");
		server.verify();
	}

	@Test
	void removeLabelAsksGitlabToDropThatLabel() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		server.expect(requestTo(
				"https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(header("Authorization", "Bearer tok"))
				.andExpect(content().json("{\"remove_labels\":\"ai/managed\"}"))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		client.removeLabel("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 21, "ai/managed");
		server.verify();
	}

	@Test
	void postsACommentOntoTheIssue() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		server.expect(requestTo(
				"https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21/notes"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer tok"))
				.andExpect(content().json("{\"body\":\"工作记录：写完了\"}"))
				.andRespond(withSuccess(
						"{\"id\":12,\"body\":\"工作记录：写完了\",\"system\":false,\"created_at\":\"2026-10-27T00:00:00Z\",\"author\":{\"name\":\"人\"}}",
						MediaType.APPLICATION_JSON));

		assertThat(client.comment("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 21, "工作记录：写完了").id())
				.isEqualTo(12);
		server.verify();
	}

	@Test
	void readsTheDescriptionWithTheSessionToken() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		server.expect(requestTo(
				"https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer tok"))
				.andRespond(withSuccess("{\"iid\":21,\"description\":\"正文\",\"title\":\"标题\"}", MediaType.APPLICATION_JSON));

		assertThat(client.description("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 21))
				.isEqualTo("正文");
		server.verify();
	}

	@Test
	void missingDescriptionIsEmpty() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		server.expect(requestTo(
				"https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21"))
				.andRespond(withSuccess("{\"iid\":21,\"description\":null}", MediaType.APPLICATION_JSON));

		assertThat(client.description("tok", "https://hackers.oalite.com/", "qingflow-develop", "qingflow-workspace", 21))
				.isEmpty();
	}

	@Test
	void commentsSkipSystemNotesAndFollowPages() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		String notes = "/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21/notes?per_page=100&sort=asc&order_by=created_at&page=";
		server.expect(requestTo("https://hackers.oalite.com" + notes + "1"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer tok"))
				.andRespond(withSuccess("""
						[{"id":1,"body":"第一条","system":false,"created_at":"2026-08-06T01:02:03Z","author":{"name":"Ada","username":"ada"}},
						{"id":2,"body":"changed the description","system":true,"created_at":"2026-08-06T01:03:03Z","author":{"username":"bot"}}]
						""", MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));
		server.expect(requestTo("https://hackers.oalite.com" + notes + "2"))
				.andRespond(withSuccess("""
						[{"id":3,"body":"  ","system":false,"created_at":"2026-08-06T02:00:00Z","author":{"name":"","username":"bea"}},
						{"id":4,"body":"第二条","system":false,"created_at":"2026-08-07T03:00:00Z","author":{"username":"bea"}}]
						""", MediaType.APPLICATION_JSON));

		assertThat(client.comments("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 21))
				.containsExactly(new GitlabComment(1, "Ada", "2026-08-06T01:02:03Z", "第一条"),
						new GitlabComment(4, "bea", "2026-08-07T03:00:00Z", "第二条"));
		server.verify();
	}

	@Test
	void listsOpenedIssuesAndKeepsTheirLabels() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		String issues = "https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues?state=opened&per_page=100&page=";
		server.expect(requestTo(issues + "1"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer tok"))
				.andRespond(withSuccess("""
						[{"iid":21,"labels":["status/proposal","ai/managed"]},
						{"iid":22,"labels":["type/bug"]}]
						""", MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));
		server.expect(requestTo(issues + "2"))
				.andRespond(withSuccess("""
						[{"iid":23}]
						""", MediaType.APPLICATION_JSON));

		assertThat(client.opened("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace"))
				.containsExactly(new GitlabIssueClient.OpenedIssue(21, java.util.List.of("status/proposal", "ai/managed")),
						new GitlabIssueClient.OpenedIssue(22, java.util.List.of("type/bug")),
						new GitlabIssueClient.OpenedIssue(23, java.util.List.of()));
		server.verify();
	}

	@Test
	void listsEveryIssueAndFollowsTheNextPage() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		String issues = "https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues?state=all&per_page=100&page=";
		server.expect(requestTo(issues + "1"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer tok"))
				.andRespond(withSuccess("""
						[{"iid":21,"title":"主任务","description":"<!-- helix -->\\n状态：实现中\\n<!-- /helix -->","state":"opened","author":{"username":"ada"},"assignees":[{"name":"陈"}]}]
						""", MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));
		server.expect(requestTo(issues + "2"))
				.andRespond(withSuccess("""
						[{"iid":36,"title":"已关闭的","description":"","state":"closed","author":{"username":"bea"},"assignees":[]}]
						""", MediaType.APPLICATION_JSON));

		assertThat(client.issues("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace"))
				.containsExactly(
						new GitlabIssueClient.IssueHead(21, "主任务", "<!-- helix -->\n状态：实现中\n<!-- /helix -->", "ada", "陈",
								"opened", java.util.List.of()),
						new GitlabIssueClient.IssueHead(36, "已关闭的", "", "bea", "", "closed", java.util.List.of()));
		server.verify();
	}

	@Test
	void stopsAtTheIssueLimit() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		String issues = "https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues?state=all&per_page=100&page=";
		server.expect(requestTo(issues + "1"))
				.andRespond(withSuccess("""
						[{"iid":21,"title":"第一条","description":"","state":"opened","author":{"username":"ada"}},
						{"iid":22,"title":"第二条","description":"","state":"opened","author":{"username":"bea"}}]
						""", MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));

		assertThat(client.issues("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 1))
				.extracting(GitlabIssueClient.IssueHead::iid)
				.containsExactly(21);
		server.verify();
	}

	@Test
	void unauthorizedKeepsTheStatus() {
		assertStatus(withUnauthorizedRequest(), 401);
	}

	@Test
	void serverErrorKeepsTheStatus() {
		assertStatus(withServerError(), 500);
	}

	private static void assertStatus(org.springframework.test.web.client.ResponseCreator response, int status) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		GitlabIssueClient client = new GitlabIssueClient(builder.build());
		server.expect(requestTo(
				"https://hackers.oalite.com/api/v4/projects/qingflow-develop%2Fqingflow-workspace/issues/21"))
				.andRespond(response);

		assertThatThrownBy(
				() -> client.description("tok", "hackers.oalite.com", "qingflow-develop", "qingflow-workspace", 21))
				.isInstanceOf(GitlabIssueException.class)
				.extracting(exception -> ((GitlabIssueException) exception).status())
				.isEqualTo(status);
	}

}
