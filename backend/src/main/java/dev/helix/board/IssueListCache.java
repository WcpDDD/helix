package dev.helix.board;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import dev.helix.gitlab.GitlabIssueClient.IssueHead;

import org.springframework.stereotype.Component;

/**
 * 任务列表的内存副本。后台同步整表写入；创建和改状态先叠在副本上，避免等下一轮同步。
 */
@Component
public class IssueListCache {

	static final int LIMIT = 500;

	private final ConcurrentMap<Long, Slot> slots = new ConcurrentHashMap<>();

	public List<IssueHead> get(long projectId) {
		Slot slot = slots.get(projectId);
		if (slot == null) {
			return null;
		}
		synchronized (slot) {
			if (slot.heads == null) {
				return null;
			}
			if (slot.overlay.isEmpty()) {
				return slot.heads;
			}
			Map<Integer, IssueHead> byNumber = new LinkedHashMap<>();
			for (IssueHead head : slot.heads) {
				byNumber.put(head.iid(), head);
			}
			for (Overlay overlay : slot.overlay.values()) {
				byNumber.put(overlay.head.iid(), overlay.head);
			}
			return List.copyOf(byNumber.values());
		}
	}

	public int begin(long projectId) {
		Slot slot = slots.computeIfAbsent(projectId, id -> new Slot());
		synchronized (slot) {
			return slot.generation.get();
		}
	}

	public void store(long projectId, int ticket, List<IssueHead> heads) {
		Slot slot = slots.computeIfAbsent(projectId, id -> new Slot());
		List<IssueHead> snapshot = heads == null ? List.of() : List.copyOf(heads);
		synchronized (slot) {
			if (slot.generation.get() != ticket) {
				return;
			}
			slot.heads = snapshot;
			slot.overlay.entrySet().removeIf(entry -> entry.getValue().generation <= ticket);
		}
	}

	public void revise(long projectId, int iid, String description, List<String> labels, boolean close) {
		Slot slot = slots.get(projectId);
		if (slot == null) {
			return;
		}
		synchronized (slot) {
			IssueHead current = current(slot, iid);
			if (current == null) {
				return;
			}
			String state = close ? "closed" : current.state();
			IssueHead next = new IssueHead(iid, current.title(), description, current.author(), current.assignee(), state,
					List.copyOf(labels));
			put(slot, next);
		}
	}

	public void insert(long projectId, IssueHead head) {
		if (head == null) {
			return;
		}
		Slot slot = slots.computeIfAbsent(projectId, id -> new Slot());
		synchronized (slot) {
			put(slot, head);
		}
	}

	private static void put(Slot slot, IssueHead head) {
		int generation = slot.generation.incrementAndGet();
		slot.overlay.put(head.iid(), new Overlay(generation, head));
	}

	private static IssueHead current(Slot slot, int iid) {
		Overlay overlay = slot.overlay.get(iid);
		if (overlay != null) {
			return overlay.head;
		}
		if (slot.heads == null) {
			return null;
		}
		for (IssueHead head : slot.heads) {
			if (head.iid() == iid) {
				return head;
			}
		}
		return null;
	}

	private static final class Slot {

		private final AtomicInteger generation = new AtomicInteger();

		private final Map<Integer, Overlay> overlay = new LinkedHashMap<>();

		private List<IssueHead> heads;

	}

	private record Overlay(int generation, IssueHead head) {
	}

}
