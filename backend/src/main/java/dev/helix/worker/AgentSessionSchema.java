package dev.helix.worker;

import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentSessionSchema implements ApplicationRunner {

	private final JdbcTemplate jdbc;

	public AgentSessionSchema(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		jdbc.update("UPDATE agent_session SET agent_id = 0 WHERE agent_id IS NULL");
		List<Map<String, Object>> indexes = jdbc.queryForList(
				"SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'agent_session'");
		for (Map<String, Object> index : indexes) {
			String sql = index.get("sql") == null ? "" : index.get("sql").toString();
			String name = index.get("name") == null ? "" : index.get("name").toString();
			if (name.isBlank() || sql.contains("agent_id")) {
				continue;
			}
			if (sql.contains("issue_number")) {
				jdbc.execute("DROP INDEX IF EXISTS \"" + name.replace("\"", "") + "\"");
			}
		}
		jdbc.execute("""
				CREATE UNIQUE INDEX IF NOT EXISTS agent_session_issue_agent
				ON agent_session(project_id, issue_number, agent_id)
				""");
	}

}
