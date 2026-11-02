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
		List<Map<String, Object>> pending = jdbc.queryForList("""
				SELECT id, project_id, issue_number, agent_name
				FROM agent_session
				WHERE agent_id IS NULL
				ORDER BY id
				""");
		for (Map<String, Object> row : pending) {
			long id = ((Number) row.get("id")).longValue();
			long projectId = ((Number) row.get("project_id")).longValue();
			int issue = ((Number) row.get("issue_number")).intValue();
			String name = row.get("agent_name") == null ? "" : row.get("agent_name").toString();
			long assigned = name.isBlank() && !taken(projectId, issue, 0) ? 0 : nextId();
			jdbc.update("UPDATE agent_session SET agent_id = ? WHERE id = ?", assigned, id);
		}
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

	private boolean taken(long projectId, int issue, long agentId) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM agent_session WHERE project_id = ? AND issue_number = ? AND agent_id = ?",
				Integer.class, projectId, issue, agentId);
		return count != null && count > 0;
	}

	private long nextId() {
		Long max = jdbc.queryForObject("SELECT COALESCE(MAX(agent_id), 0) FROM agent_session", Long.class);
		long next = max == null ? 1 : max + 1;
		return next <= 0 ? 1 : next;
	}

}
