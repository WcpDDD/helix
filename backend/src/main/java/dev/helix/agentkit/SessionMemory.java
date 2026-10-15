package dev.helix.agentkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.agentkit.api.AgentRunningContext;
import io.agentkit.api.session.SessionStorage;
import io.agentkit.api.store.Store;
import io.agentkit.execution.session.DefaultSessionStorage;
import io.agentkit.execution.session.repository.SessionEntry;
import io.agentkit.execution.session.repository.SessionEntryRepository;
import io.agentkit.execution.store.StoreSessionStateRepository;

final class SessionMemory {

	private SessionMemory() {
	}

	static SessionStorage open(Path directory) {
		Store store = directory == null ? new MemoryStore() : new DirectoryStore(directory.toAbsolutePath().normalize());
		return new DefaultSessionStorage(new EntryFiles(directory), new StoreSessionStateRepository(store));
	}

	private static final class EntryFiles implements SessionEntryRepository {

		private final Path directory;

		private final JsonMapper json = new JsonMapper();

		private final ConcurrentHashMap<String, SessionEntry> entries = new ConcurrentHashMap<>();

		private EntryFiles(Path directory) {
			this.directory = directory == null ? null : directory.toAbsolutePath().normalize().resolve("entries");
		}

		@Override
		public void insert(AgentRunningContext context, SessionEntry entry) {
			write(entry);
		}

		@Override
		public Optional<SessionEntry> find(AgentRunningContext context, String threadId) {
			SessionEntry cached = entries.get(threadId);
			if (cached != null) {
				return Optional.of(cached);
			}
			SessionEntry loaded = read(threadId);
			if (loaded == null) {
				return Optional.empty();
			}
			entries.put(threadId, loaded);
			return Optional.of(loaded);
		}

		@Override
		public void update(AgentRunningContext context, SessionEntry entry) {
			write(entry);
		}

		@Override
		public void delete(AgentRunningContext context, String threadId) {
			entries.remove(threadId);
			if (directory == null) {
				return;
			}
			try {
				Files.deleteIfExists(file(threadId));
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}

		private void write(SessionEntry entry) {
			entries.put(entry.threadId(), entry);
			if (directory == null) {
				return;
			}
			ObjectNode node = json.createObjectNode();
			node.put("threadId", entry.threadId());
			if (entry.tenantKey() != null) {
				node.put("tenantKey", entry.tenantKey());
			}
			node.put("status", entry.status().name());
			node.put("createdAt", entry.createdAt().toEpochMilli());
			if (entry.destroyedAt() != null) {
				node.put("destroyedAt", entry.destroyedAt().toEpochMilli());
			}
			Path file = file(entry.threadId());
			Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(temporary, json.writeValueAsString(node));
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}

		private SessionEntry read(String threadId) {
			if (directory == null) {
				return null;
			}
			Path file = file(threadId);
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try {
				JsonNode node = json.readTree(Files.readString(file));
				Instant destroyed = node.hasNonNull("destroyedAt")
						? Instant.ofEpochMilli(node.path("destroyedAt").asLong())
						: null;
				String tenant = node.hasNonNull("tenantKey") ? node.path("tenantKey").asString() : null;
				return new SessionEntry(
						node.path("threadId").asString(threadId),
						tenant,
						io.agentkit.api.session.SessionStatus.valueOf(node.path("status").asString("IDLE")),
						Instant.ofEpochMilli(node.path("createdAt").asLong()),
						destroyed);
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
			catch (RuntimeException exception) {
				return null;
			}
		}

		private Path file(String threadId) {
			return directory.resolve(safe(threadId) + ".json");
		}
	}

	private static final class MemoryStore implements Store {

		private final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap<>();

		@Override
		public void put(AgentRunningContext context, String key, InputStream body) {
			try {
				files.put(key, body.readAllBytes());
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}

		@Override
		public Optional<InputStream> get(AgentRunningContext context, String key) {
			byte[] body = files.get(key);
			if (body == null) {
				return Optional.empty();
			}
			return Optional.of(new ByteArrayInputStream(body));
		}

		@Override
		public void delete(AgentRunningContext context, String key) {
			files.remove(key);
		}

		@Override
		public List<String> list(AgentRunningContext context, String prefix) {
			return List.of();
		}

		@Override
		public void close() {
		}
	}

	private static final class DirectoryStore implements Store {

		private final Path root;

		private DirectoryStore(Path root) {
			this.root = root;
		}

		@Override
		public void put(AgentRunningContext context, String key, InputStream body) {
			try {
				byte[] bytes = body.readAllBytes();
				Path file = file(key);
				Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
				Files.createDirectories(file.getParent());
				Files.write(temporary, bytes);
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}

		@Override
		public Optional<InputStream> get(AgentRunningContext context, String key) {
			Path file = file(key);
			if (!Files.isRegularFile(file)) {
				return Optional.empty();
			}
			try {
				return Optional.of(Files.newInputStream(file));
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}

		@Override
		public void delete(AgentRunningContext context, String key) {
			try {
				Files.deleteIfExists(file(key));
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}

		@Override
		public List<String> list(AgentRunningContext context, String prefix) {
			return List.of();
		}

		@Override
		public void close() {
		}

		private Path file(String key) {
			String relative = key == null ? "" : key.replace('\\', '/');
			while (relative.startsWith("/")) {
				relative = relative.substring(1);
			}
			if (relative.isBlank() || relative.contains("..")) {
				throw new IllegalArgumentException("session key");
			}
			Path file = root.resolve(relative).normalize();
			if (!file.startsWith(root)) {
				throw new IllegalArgumentException("session key");
			}
			return file;
		}
	}

	private static String safe(String threadId) {
		if (threadId == null || threadId.isBlank() || threadId.contains("/") || threadId.contains("\\") || threadId.contains("..")) {
			throw new IllegalArgumentException("threadId");
		}
		return threadId;
	}

}
