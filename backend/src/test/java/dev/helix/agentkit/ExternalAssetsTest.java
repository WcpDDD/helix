package dev.helix.agentkit;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAssetsTest {

	@Test
	void freshHomeHasNoCatalogs() throws Exception {
		Path home = Files.createTempDirectory("helix-assets");
		try (ExternalAssets assets = ExternalAssets.open(home)) {
			assertThat(assets.catalogs()).isEmpty();
			assertThat(assets.mounting()).isNotNull();
		}
	}

}
