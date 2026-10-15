package dev.helix.board;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentSchema implements ApplicationRunner {

	private final JdbcTemplate jdbc;

	public AgentSchema(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		jdbc.execute("DROP INDEX IF EXISTS agent_singleton_tag");
		jdbc.execute("""
				CREATE UNIQUE INDEX agent_singleton_tag
				ON agent(project_id, tag)
				WHERE tag IS NOT NULL
				""");
	}

}
