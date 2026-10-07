package dev.helix.gitlab;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class GitlabIssueClientTest {

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
