package dev.helix.work;

import java.util.List;

public record WorkSnapshot(String branch, String directory, String push, String pushError, List<Commit> commits,
		int ahead, int behind, boolean dirty) {

	public record Commit(String sha, String subject, String author, String at) {
	}

}
