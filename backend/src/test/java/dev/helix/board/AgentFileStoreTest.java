package dev.helix.board;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.helix.board.AgentService.AgentDraft;
import dev.helix.work.ProjectWorkspaces;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFileStoreTest {

	@TempDir
	private Path temp;

	@Test
	void documentKeepsTheBodyAfterTheFrontmatter() {
		AgentEntity agent = new AgentEntity(null, "review", "第一行\n---\n还在正文");
		agent.retag("reviewer");
		agent.replaceSkills(List.of("skills/dev-flow/SKILL.md", "skills/review/SKILL.md"));

		AgentEntity read = new AgentEntity(null, "review", "");
		AgentDocument.readInto(read, AgentDocument.render(agent));

		assertThat(read.getTag()).isEqualTo("reviewer");
		assertThat(read.getSkills()).extracting(AgentSkillEntity::getSkillPath)
				.containsExactly("skills/dev-flow/SKILL.md", "skills/review/SKILL.md");
		assertThat(read.getBody()).isEqualTo("第一行\n---\n还在正文");

		AgentEntity plain = new AgentEntity(null, "bug分析", "");
		AgentDocument.readInto(plain, "---\n---\n\n只看正文。");
		assertThat(plain.getTag()).isNull();
		assertThat(plain.getScope()).isEqualTo("global");
		assertThat(plain.getAccess()).containsExactly("reporter", "developer", "maintainer");
		assertThat(plain.getBody()).isEqualTo("只看正文。");

		assertThat(AgentAccess.allows("developer", List.of("developer", "maintainer"))).isTrue();
		assertThat(AgentAccess.allows("reporter", List.of("developer"))).isFalse();
		assertThat(AgentAccess.allows("guest", List.of("developer"))).isFalse();
		assertThat(AgentAccess.seesCatalog("maintainer")).isTrue();
		assertThat(AgentAccess.seesCatalog("developer")).isFalse();
		assertThat(AgentAccess.scope("global", "developer")).isEqualTo("global");
	}

	@Test
	void agentsLiveInTheWorkspaceUnderTheirDirectoryName() throws Exception {
		ProjectEntity project = new ProjectEntity("demo", "example.test", "owner", "repo");
		setId(project, 4L);
		ProjectRepository projects = mock(ProjectRepository.class);
		when(projects.findById(4L)).thenReturn(Optional.of(project));
		when(projects.findAll()).thenReturn(List.of(project));

		ProjectWorkspaces workspaces = new ProjectWorkspaces(temp, null, null);
		Path repo = workspaces.directory(project);
		Files.createDirectories(repo);
		git(repo, "init", "-b", "main");
		git(repo, "commit", "--allow-empty", "-m", "init");
		git(repo, "sparse-checkout", "set", "skills");

		JdbcTemplate jdbc = sqlite();
		jdbc.update("insert into agent(id, name, body, project_id, tag) values (12, 'proposal', '审字段。', 4, 'proposal')");
		jdbc.update("insert into agent_skill(id, position, skill_path, agent_id) values (1, 0, 'skills/dev-flow/SKILL.md', 12)");

		WorkspaceAgentRepository repository = new WorkspaceAgentRepository(projects, workspaces, jdbc);
		List<AgentEntity> imported = repository.findByProject_IdOrderByNameAsc(4L);

		assertThat(imported).extracting(AgentEntity::getName).containsExactly("proposal");
		assertThat(imported.getFirst().getBody()).isEqualTo("审字段。");
		assertThat(imported.getFirst().getSkills()).extracting(AgentSkillEntity::getSkillPath)
				.containsExactly("skills/dev-flow/SKILL.md");
		assertThat(Files.readString(repo.resolve("agents/proposal/agent.md"))).doesNotContain("id:").contains("审字段。");
		assertThat(gitText(repo, "sparse-checkout", "list")).contains("agents");
		assertThat(jdbc.queryForObject("select count(*) from agent", Number.class).intValue()).isZero();

		AgentService service = new AgentService(repository);
		var created = service.create(project, new AgentDraft("review", "先这样。", List.of("skills/review/SKILL.md"), "reviewer"));
		assertThat(created.name()).isEqualTo("review");
		assertThat(Files.readString(repo.resolve("agents/review/agent.md"))).contains("tag: reviewer").contains("scope: task");

		var renamed = service.update(project, "review", new AgentDraft("复审", "改过了。", List.of(), null));
		assertThat(renamed.name()).isEqualTo("复审");
		assertThat(repo.resolve("agents/review")).doesNotExist();
		assertThat(Files.readString(repo.resolve("agents/复审/agent.md"))).contains("改过了。").doesNotContain("id:");

		service.delete(project, "proposal");
		assertThat(repo.resolve("agents/proposal")).doesNotExist();
		assertThat(repository.findByProject_IdAndName(4L, "复审")).isPresent();
	}

	private JdbcTemplate sqlite() {
		Path database = temp.resolve("agents.db");
		DriverManagerDataSource source = new DriverManagerDataSource();
		source.setDriverClassName("org.sqlite.JDBC");
		source.setUrl("jdbc:sqlite:" + database);
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.execute("""
				create table agent (
					id integer primary key,
					body text not null,
					name varchar(255) not null,
					project_id bigint not null,
					tag varchar(255)
				)
				""");
		jdbc.execute("""
				create table agent_skill (
					id integer primary key,
					position integer not null,
					skill_path varchar(255) not null,
					agent_id bigint not null
				)
				""");
		return jdbc;
	}

	private static void git(Path directory, String... args) throws Exception {
		assertThat(gitText(directory, args)).isNotNull();
	}

	private static String gitText(Path directory, String... args) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.add("-c");
		command.add("user.name=Helix");
		command.add("-c");
		command.add("user.email=helix@localhost");
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertThat(process.waitFor()).as(output).isZero();
		return output;
	}

	private static void setId(ProjectEntity project, long id) throws Exception {
		var field = ProjectEntity.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(project, id);
	}

}
