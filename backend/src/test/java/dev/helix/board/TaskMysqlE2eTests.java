package dev.helix.board;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TaskMysqlE2eTests {

	@Container
	@ServiceConnection
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

	@DynamicPropertySource
	static void mysql(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper json;

	@Test
	void boardListsQingflowIssuesFromMysql() throws Exception {
		TaskApiTests.assertImported(json.readTree(mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString()));
	}

}
