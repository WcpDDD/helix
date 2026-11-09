package dev.helix.mcp;

import java.util.List;

import dev.helix.board.ProposalDraft;

final class ProposalFields {

	private ProposalFields() {
	}

	static List<String> feature() {
		return ProposalDraft.fields("feature");
	}

	static List<String> bug() {
		return ProposalDraft.fields("bug");
	}

}
