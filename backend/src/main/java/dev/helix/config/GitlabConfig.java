package dev.helix.config;

import java.time.Duration;

import dev.helix.auth.GitlabOAuthClient;
import dev.helix.gitlab.GitlabDirectoryClient;
import dev.helix.gitlab.GitlabIssueClient;
import dev.helix.gitlab.GitlabProjectClient;
import dev.helix.gitlab.GitlabRepositoryClient;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(HelixProperties.class)
public class GitlabConfig {

	private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);

	private static final Timeout READ_TIMEOUT = Timeout.ofSeconds(30);

	@Bean(destroyMethod = "close")
	CloseableHttpClient gitlabHttpClient() {
		ConnectionConfig connectionConfig = ConnectionConfig.custom()
				.setConnectTimeout(CONNECT_TIMEOUT)
				.setSocketTimeout(READ_TIMEOUT)
				.setValidateAfterInactivity(TimeValue.ofSeconds(2))
				.setTimeToLive(TimeValue.ofMinutes(5))
				.build();
		PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
				.setMaxConnTotal(20)
				.setMaxConnPerRoute(20)
				.setDefaultConnectionConfig(connectionConfig)
				.build();
		return HttpClients.custom()
				.setConnectionManager(connections)
				.evictExpiredConnections()
				.evictIdleConnections(TimeValue.ofSeconds(45))
				.build();
	}

	@Bean
	GitlabOAuthClient gitlabOAuthClient(HelixProperties properties, CloseableHttpClient gitlabHttpClient) {
		return new GitlabOAuthClient(properties.gitlab(), http(gitlabHttpClient));
	}

	@Bean
	GitlabIssueClient gitlabIssueClient(CloseableHttpClient gitlabHttpClient) {
		return new GitlabIssueClient(http(gitlabHttpClient));
	}

	@Bean
	GitlabDirectoryClient gitlabDirectoryClient(CloseableHttpClient gitlabHttpClient) {
		return new GitlabDirectoryClient(http(gitlabHttpClient));
	}

	@Bean
	GitlabProjectClient gitlabProjectClient(CloseableHttpClient gitlabHttpClient) {
		return new GitlabProjectClient(http(gitlabHttpClient));
	}

	@Bean
	GitlabRepositoryClient gitlabRepositoryClient(CloseableHttpClient gitlabHttpClient) {
		return new GitlabRepositoryClient(http(gitlabHttpClient));
	}

	private static RestClient http(CloseableHttpClient client) {
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(client);
		factory.setConnectionRequestTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(30));
		return RestClient.builder().requestFactory(factory).build();
	}

}
