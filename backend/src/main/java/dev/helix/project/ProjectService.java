package dev.helix.project;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ProjectService {

	private final List<Project> projects = List.of(
			new Project("p-1", "传感器固件 2.4", "完成量产前的稳定性验证", 62, "进行中", "硬件组"),
			new Project("p-2", "实验数据管道", "把采集结果接到进度看板", 35, "进行中", "平台组"),
			new Project("p-3", "协作纪要助手", "根据周报起草风险与下一步", 18, "筹备中", "人机协同")
	);

	public List<Project> list() {
		return projects;
	}

}
