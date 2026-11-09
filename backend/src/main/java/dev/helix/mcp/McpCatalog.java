package dev.helix.mcp;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.helix.board.ProposalDraft;

/**
 * MCP 工具说明。agent 只看这份目录，就应知道能提交需求和缺陷，以及各自要哪些字段。
 */
final class McpCatalog {

	static final String TOOL = "submit_task";

	private static final JsonMapper JSON = new JsonMapper();

	private McpCatalog() {
	}

	static String instructions() {
		return """
				用 submit_task 在 Helix 创建任务，并委托 proposal agent 写出详细方案。
				kind 只能是 feature 或 bug。
				feature 是需求，fields 每一项都要写：%s。
				bug 是缺陷，fields 每一项都要写：%s。
				fields 的键用这些中文名，值写具体内容，不能空。title 用一句话概括。
				""".formatted(joined("feature"), joined("bug")).strip();
	}

	static JsonNode tools() {
		ObjectNode tool = JSON.createObjectNode();
		tool.put("name", TOOL);
		tool.put("description", instructions());
		tool.set("inputSchema", schema());
		ArrayNode tools = JSON.createArrayNode();
		tools.add(tool);
		ObjectNode listed = JSON.createObjectNode();
		listed.set("tools", tools);
		return listed;
	}

	private static ObjectNode schema() {
		ObjectNode schema = JSON.createObjectNode();
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		ObjectNode properties = schema.putObject("properties");
		ObjectNode kind = properties.putObject("kind");
		kind.put("type", "string");
		kind.put("description", "feature 是需求，bug 是缺陷。两种要填的 fields 不同，见本工具说明。");
		ArrayNode enums = kind.putArray("enum");
		enums.add("feature");
		enums.add("bug");
		ObjectNode title = properties.putObject("title");
		title.put("type", "string");
		title.put("description", "任务标题。需求写要做什么，缺陷写现象。");
		ObjectNode fields = properties.putObject("fields");
		fields.put("type", "object");
		fields.put("description", "需求 feature 必须提供：" + joined("feature") + "。缺陷 bug 必须提供：" + joined("bug") + "。键用这些中文名，值不能空。");
		fields.put("additionalProperties", false);
		ObjectNode fieldProps = fields.putObject("properties");
		for (String name : names("feature")) {
			fieldProps.putObject(name).put("type", "string").put("description", "需求 feature 必填。");
		}
		for (String name : names("bug")) {
			fieldProps.putObject(name).put("type", "string").put("description", "缺陷 bug 必填。");
		}
		ArrayNode required = schema.putArray("required");
		required.add("kind");
		required.add("title");
		required.add("fields");
		ArrayNode oneOf = schema.putArray("oneOf");
		oneOf.add(branch("feature", "需求"));
		oneOf.add(branch("bug", "缺陷"));
		return schema;
	}

	private static ObjectNode branch(String kind, String label) {
		ObjectNode branch = JSON.createObjectNode();
		ObjectNode properties = branch.putObject("properties");
		properties.putObject("kind").put("const", kind).put("description", label);
		ObjectNode fields = properties.putObject("fields");
		fields.put("type", "object");
		fields.put("additionalProperties", false);
		ObjectNode fieldProps = fields.putObject("properties");
		ArrayNode fieldRequired = fields.putArray("required");
		for (String name : names(kind)) {
			fieldProps.putObject(name).put("type", "string").put("minLength", 1);
			fieldRequired.add(name);
		}
		return branch;
	}

	private static String joined(String kind) {
		return String.join("、", names(kind));
	}

	private static List<String> names(String kind) {
		List<String> fields = ProposalDraft.fields(kind);
		return fields == null ? List.of() : fields;
	}

}
