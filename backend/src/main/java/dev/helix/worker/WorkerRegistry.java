package dev.helix.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkerRegistry {

	private final WorkerRepository workers;

	private final Duration lease;

	public WorkerRegistry(WorkerRepository workers, @Value("${helix.worker.lease:15s}") Duration lease) {
		this.workers = workers;
		this.lease = lease == null || lease.isZero() || lease.isNegative() ? Duration.ofSeconds(15) : lease;
	}

	@Transactional
	public WorkerView heartbeat(String id, String name) {
		String workerId = required(id, "worker id 不能空。");
		String workerName = required(name, "worker 名字不能空。");
		WorkerEntity worker = workers.findById(workerId).orElseGet(() -> new WorkerEntity(workerId, workerName, Instant.now()));
		worker.rename(workerName);
		worker.touch(Instant.now());
		workers.save(worker);
		return view(worker);
	}

	@Transactional(readOnly = true)
	public List<WorkerView> list() {
		return workers.findAll().stream()
				.sorted(Comparator.comparing(WorkerEntity::getId))
				.map(this::view)
				.toList();
	}

	@Transactional(readOnly = true)
	public boolean alive(String workerId) {
		return workers.findById(workerId).filter(this::alive).isPresent();
	}

	@Transactional(readOnly = true)
	public WorkerEntity requireLive() {
		return workers.findAll().stream()
				.filter(this::alive)
				.max(Comparator.comparing(WorkerEntity::getLastHeartbeat))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有存活的 worker。"));
	}

	private boolean alive(WorkerEntity worker) {
		return !worker.getLastHeartbeat().isBefore(Instant.now().minus(lease));
	}

	private WorkerView view(WorkerEntity worker) {
		return new WorkerView(worker.getId(), worker.getName(), alive(worker), worker.getLastHeartbeat());
	}

	private static String required(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
		}
		return value.strip();
	}

	public record WorkerView(String id, String name, boolean alive, Instant lastHeartbeat) {
	}

}
