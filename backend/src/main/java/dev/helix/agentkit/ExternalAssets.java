package dev.helix.agentkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.agentkit.api.IdentityContext;
import io.agentkit.api.Principal;
import io.agentkit.api.asset.AssetCaller;
import io.agentkit.api.asset.AssetCatalog;
import io.agentkit.api.asset.AssetStoreMount;
import io.agentkit.api.store.Store;
import io.agentkit.asset.AssetFacade;
import io.agentkit.asset.DefaultAssetService;
import io.agentkit.asset.catalog.DefaultCatalogVersioning;
import io.agentkit.asset.catalog.StaticAssetStoreMountProvider;
import io.agentkit.asset.catalog.repository.JpaCatalogRepository;
import io.agentkit.asset.definition.DefaultEntryDecoder;
import io.agentkit.asset.definition.DefaultEntryHeaderDecoder;
import io.agentkit.asset.definition.DefaultEntrySpecDecoderRegistry;
import io.agentkit.asset.definition.DocumentSpecV1Decoder;
import io.agentkit.asset.definition.LocalMcpSpecV1Decoder;
import io.agentkit.asset.definition.RemoteMcpSpecV1Decoder;
import io.agentkit.asset.definition.SkillSpecV1Decoder;
import io.agentkit.execution.assembly.CatalogMounting;
import io.agentkit.execution.assembly.StoreCopyVolumeProvider;
import io.agentkit.infra.db.HibernateDbProvider;
import io.agentkit.infra.db.SqliteConfig;
import io.agentkit.infra.db.StaticDbConfigProvider;
import io.agentkit.infra.store.CompositeStoreProvider;
import io.agentkit.infra.store.FsStoreConfig;
import io.agentkit.infra.store.FsStoreProvider;
import io.agentkit.infra.store.StaticStoreConfigProvider;
import io.agentkit.infra.store.StoreMount;

/**
 * 本机外部资产目录。blob 和目录库都在用户目录，安装时拷进工作区外面的资产目录。
 */
public final class ExternalAssets implements AutoCloseable {

	private final AssetFacade assets;

	private final Store store;

	private final CatalogMounting mounting;

	private ExternalAssets(AssetFacade assets, Store store, CatalogMounting mounting) {
		this.assets = assets;
		this.store = store;
		this.mounting = mounting;
	}

	public static ExternalAssets open() throws IOException {
		return open(Path.of(System.getProperty("user.home"), ".agentkit", "helix"));
	}

	public static ExternalAssets open(Path home) throws IOException {
		Path root = home.toAbsolutePath().normalize();
		Files.createDirectories(root);
		Path blobs = root.resolve("blobs");
		Files.createDirectories(blobs);
		Store store = new CompositeStoreProvider(List.of(new StoreMount("fs", new FsStoreProvider(
				new StaticStoreConfigProvider(FsStoreConfig.defaults(blobs)))))).open();
		var mounts = new StaticAssetStoreMountProvider(new AssetStoreMount("fs"));
		HibernateDbProvider database = new HibernateDbProvider(new StaticDbConfigProvider(new SqliteConfig(
				"sqlite",
				"jdbc:sqlite:" + root.resolve("catalog.db").toAbsolutePath(),
				"",
				"",
				"helix-assets",
				Map.of(
						"hibernate.dialect", "io.agentkit.infra.db.AgentkitSqliteDialect",
						"hibernate.hbm2ddl.auto", "update",
						"hibernate.hikari.maximumPoolSize", "1"),
				null)), JpaCatalogRepository.entities());
		JpaCatalogRepository repository = new JpaCatalogRepository(database);
		AssetFacade assets = new AssetFacade(new DefaultAssetService(
				repository,
				new DefaultCatalogVersioning(repository, store, mounts),
				decoder(),
				store,
				mounts));
		return new ExternalAssets(assets, store, new CatalogMounting(assets, new StoreCopyVolumeProvider(), store));
	}

	public CatalogMounting mounting() {
		return mounting;
	}

	public List<AssetCatalog> catalogs() {
		return assets.catalogs(new AssetCaller(new IdentityContext(Principal.service("helix"), null), null));
	}

	@Override
	public void close() {
		store.close();
	}

	private static DefaultEntryDecoder decoder() {
		return new DefaultEntryDecoder(
				new DefaultEntryHeaderDecoder(),
				new DefaultEntrySpecDecoderRegistry(List.of(
						new DocumentSpecV1Decoder(),
						new SkillSpecV1Decoder(),
						new RemoteMcpSpecV1Decoder(),
						new LocalMcpSpecV1Decoder())));
	}

}
