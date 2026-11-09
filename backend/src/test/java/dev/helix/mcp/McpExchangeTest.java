package dev.helix.mcp;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.helix.board.ProposalDraft;

import org.junit.jupiter.api.Test;

import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class McpExchangeTest {

	private final JsonMapper json = new JsonMapper();

	@Test
	void theCatalogTellsAnAgentWhatARequirementAndABugNeed() {
		JsonNode listed = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","id":1,"method":"tools/list"}
				"""), (kind, title, fields) -> "");

		String description = listed.at("/result/tools/0/description").asText();
		String schema = listed.at("/result/tools/0/inputSchema").toString();
		assertThat(description).contains("feature").contains("bug").contains("需求").contains("缺陷");
		for (String field : ProposalDraft.fields("feature")) {
			assertThat(description).contains(field);
			assertThat(schema).contains(field);
		}
		for (String field : ProposalDraft.fields("bug")) {
			assertThat(description).contains(field);
			assertThat(schema).contains(field);
		}
		assertThat(listed.at("/result/tools/0/inputSchema/properties/kind/enum").toString()).contains("feature").contains("bug");
	}

	@Test
	void initializeCarriesTheSameInstructions() {
		JsonNode response = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2025-03-26"}}
				"""), (kind, title, fields) -> "");

		assertThat(response.at("/result/instructions").asText()).contains("核心用户故事").contains("复现步骤");
		assertThat(response.at("/result/serverInfo/name").asText()).isEqualTo("helix");
	}

	@Test
	void aRequirementIsCreatedAndHandedToProposal() {
		List<String> seen = new ArrayList<>();
		JsonNode response = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"submit_task","arguments":{
				  "kind":"需求","title":"导出报表","fields":{"背景":"财务要月报","核心用户故事":"作为财务","业务交互流程":"点导出","做什么":"导出","怎么做":"异步","预期效果":"拿到文件","交互边界":"只导出自己的"}
				}}}
				"""), (kind, title, fields) -> {
			seen.add(McpIntake.kind(kind) + "|" + title);
			return "已创建需求 #41，并交给 proposal 写方案。";
		});

		assertThat(seen).containsExactly("feature|导出报表");
		assertThat(response.at("/result/content/0/text").asText()).contains("#41");
		assertThat(response.at("/result/isError").isMissingNode()).isTrue();
	}

	@Test
	void aBugWithAMissingFieldComesBackAsAToolError() {
		JsonNode response = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"submit_task","arguments":{
				  "kind":"bug","title":"登录超时","fields":{"复现步骤":"点登录"}
				}}}
				"""), (kind, title, fields) -> {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "还要写：期望行为、实际行为、环境、影响面。");
		});

		assertThat(response.at("/result/isError").asBoolean()).isTrue();
		assertThat(response.at("/result/content/0/text").asText()).contains("期望行为").doesNotContain("BAD_REQUEST");
	}

	@Test
	void anUnknownKindExplainsBothContracts() {
		JsonNode response = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"submit_task","arguments":{
				  "kind":"tech","title":"换框架","fields":{}
				}}}
				"""), (kind, title, fields) -> McpIntake.kind(kind));

		assertThat(response.at("/result/isError").asBoolean()).isTrue();
		String text = response.at("/result/content/0/text").asText();
		assertThat(text).contains("feature").contains("bug").contains("背景").contains("复现步骤");
	}

	@Test
	void aNotificationHasNoReply() {
		JsonNode response = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","method":"notifications/initialized"}
				"""), (kind, title, fields) -> "");

		assertThat(response).isNull();
	}

	@Test
	void fieldsMustBeAnObject() {
		JsonNode response = McpExchange.handle(json.readTree("""
				{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"submit_task","arguments":{
				  "kind":"bug","title":"登录超时","fields":"点登录就超时"
				}}}
				"""), (kind, title, fields) -> "");

		assertThat(response.at("/result/isError").asBoolean()).isTrue();
		assertThat(response.at("/result/content/0/text").asText()).contains("复现步骤");
	}

	@Test
	void theFixedTokenIsComparedAsABearer() {
		assertThat(McpController.bearerMatches("secret-token", "Bearer secret-token")).isTrue();
		assertThat(McpController.bearerMatches("secret-token", "bearer secret-token")).isTrue();
		assertThat(McpController.bearerMatches("secret-token", "Bearer other")).isFalse();
		assertThat(McpController.bearerMatches("secret-token", "secret-token")).isFalse();
		assertThat(McpController.bearerMatches("", "Bearer secret-token")).isFalse();
	}

	@Test
	void kindAliasesMapOntoTheTwoContracts() {
		assertThat(McpIntake.kind("需求")).isEqualTo("feature");
		assertThat(McpIntake.kind("缺陷")).isEqualTo("bug");
		assertThat(McpIntake.kind("BUG")).isEqualTo("bug");
	}

}
