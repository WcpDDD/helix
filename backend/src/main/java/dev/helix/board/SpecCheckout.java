package dev.helix.board;

import java.util.List;

public record SpecCheckout(String branch, String commit, List<SpecFile> files) {

	public record SpecFile(String path, String markdown) {
	}

}
