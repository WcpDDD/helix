package dev.helix.worker;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WorkerSessionClient {

	private final RestClient http;

	public WorkerSessionClient() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(Duration.ofSeconds(3));
		this.http = RestClient.builder().requestFactory(factory).build();
	}

	public void create(URI address, String body) {
		http.post()
				.uri(endpoint(address))
				.contentType(MediaType.APPLICATION_JSON)
				.body(body == null || body.isBlank() ? "{}" : body)
				.retrieve()
				.toBodilessEntity();
	}

	private static URI endpoint(URI address) {
		String text = address.toString();
		while (text.endsWith("/")) {
			text = text.substring(0, text.length() - 1);
		}
		return URI.create(text + "/sessions");
	}

}
