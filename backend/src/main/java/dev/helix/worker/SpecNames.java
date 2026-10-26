package dev.helix.worker;

public final class SpecNames {

	private SpecNames() {
	}

	public static boolean changeBranch(String branch) {
		if (branch == null) {
			return false;
		}
		String name = branch.strip();
		if (name.isEmpty() || "HEAD".equals(name) || name.contains("..") || name.contains(" ") || name.contains("\\")
				|| name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
			return false;
		}
		if (name.matches("helix/issue-[0-9]+")) {
			return false;
		}
		return name.matches("[A-Za-z0-9._/-]+");
	}

	public static String specName(String branch) {
		return branch.strip();
	}

	public static String openspecName(String branch) {
		return specName(branch).replace('/', '-').toLowerCase();
	}

	public static String directory(String branch) {
		return "openspec/changes/" + openspecName(branch);
	}

}
