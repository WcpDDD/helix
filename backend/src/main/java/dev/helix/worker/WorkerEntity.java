package dev.helix.worker;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "worker")
public class WorkerEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String name;

	@Column(name = "last_heartbeat", nullable = false)
	private Instant lastHeartbeat;

	private String address;

	protected WorkerEntity() {
	}

	public WorkerEntity(String id, String name, Instant lastHeartbeat) {
		this.id = id;
		this.name = name;
		this.lastHeartbeat = lastHeartbeat;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Instant getLastHeartbeat() {
		return lastHeartbeat;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void touch(Instant at) {
		this.lastHeartbeat = at;
	}

	public String getAddress() {
		return address;
	}

	public void locate(String address) {
		this.address = address;
	}

}
