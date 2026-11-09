package dev.helix.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {

	private static final Logger log = LoggerFactory.getLogger(McpController.class);

	private static final MediaType JSON = MediaType.APPLICATION_JSON;

	private final JsonMapper json = new JsonMapper();

	private final McpProperties properties;

	private final McpIntake intake;

	private final AtomicBoolean missingToken = new AtomicBoolean();

	public McpController(McpProperties properties, McpIntake intake) {
		this.properties = properties;
		this.intake = intake;
	}

	@PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> post(@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody String body) {
		if (!authorized(authorization)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.header("WWW-Authenticate", "Bearer realm=\"helix-mcp\"")
					.contentType(JSON)
					.body("{\"error\":\"unauthorized\"}");
		}
		JsonNode request;
		try {
			request = json.readTree(body);
		}
		catch (RuntimeException exception) {
			return ResponseEntity.badRequest().contentType(JSON)
					.body("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"JSON 解析失败。\"}}");
		}
		JsonNode response = McpExchange.handle(request, intake::submit);
		if (response == null) {
			return ResponseEntity.accepted().build();
		}
		return ResponseEntity.ok().contentType(JSON).body(json.writeValueAsString(response));
	}

	private boolean authorized(String authorization) {
		if (!properties.configured()) {
			if (missingToken.compareAndSet(false, true)) {
				log.warn("HELIX_MCP_TOKEN 没有配置，MCP 请求被拒绝");
			}
			return false;
		}
		return bearerMatches(properties.bearer(), authorization);
	}

	static boolean bearerMatches(String expected, String authorization) {
		if (expected == null || expected.isBlank()) {
			return false;
		}
		String header = authorization == null ? "" : authorization.strip();
		if (!header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
			return false;
		}
		String presented = header.substring("Bearer ".length()).strip();
		return MessageDigest.isEqual(expected.strip().getBytes(StandardCharsets.UTF_8),
				presented.getBytes(StandardCharsets.UTF_8));
	}

}
