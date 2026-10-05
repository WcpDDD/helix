package dev.helix;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthReportsOk() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.service").value("helix"));
	}

	@Test
	void projectsIncludeProgress() throws Exception {
		mockMvc.perform(get("/api/projects"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("传感器固件 2.4"))
				.andExpect(jsonPath("$[0].progress").value(62));
	}

}
