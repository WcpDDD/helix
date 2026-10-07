package dev.helix.auth;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.helix.config.HelixProperties;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

public class GitlabOAuthClient {

	private final HelixProperties.Gitlab gitlab;

	private final RestClient http;

	public GitlabOAuthClient(HelixProperties.Gitlab gitlab, RestClient http) {
		this.gitlab = gitlab;
		this.http = http;
	}

	public URI authorizeUri(String state) {
		return UriComponentsBuilder.fromUriString(gitlab.origin())
				.path("/oauth/authorize")
				.queryParam("client_id", gitlab.clientId())
				.queryParam("redirect_uri", gitlab.redirectUri())
				.queryParam("response_type", "code")
				.queryParam("state", state)
				.queryParam("scope", gitlab.scope())
				.build()
				.encode()
				.toUri();
	}

	public GitlabLogin exchange(String code) {
		TokenPayload token = requestToken(code);
		if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
			throw new GitlabOAuthException("GitLab 没有返回访问令牌");
		}
		UserPayload profile = requestUser(token.accessToken());
		if (profile == null || profile.username() == null || profile.username().isBlank()) {
			throw new GitlabOAuthException("GitLab 没有返回用户");
		}
		String name = profile.name() == null || profile.name().isBlank() ? profile.username() : profile.name();
		return new GitlabLogin(new GitlabUser(profile.id(), profile.username(), name, profile.avatarUrl()),
				token.accessToken());
	}

	private TokenPayload requestToken(String code) {
		LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", gitlab.clientId());
		form.add("client_secret", gitlab.clientSecret());
		form.add("code", code);
		form.add("grant_type", "authorization_code");
		form.add("redirect_uri", gitlab.redirectUri());
		try {
			return http.post()
					.uri(gitlab.origin() + "/oauth/token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(TokenPayload.class);
		}
		catch (RestClientResponseException exception) {
			throw new GitlabOAuthException(errorMessage(exception), exception);
		}
		catch (RestClientException exception) {
			throw new GitlabOAuthException("连不上 GitLab", exception);
		}
	}

	private UserPayload requestUser(String accessToken) {
		try {
			return http.get()
					.uri(gitlab.origin() + "/api/v4/user")
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(UserPayload.class);
		}
		catch (RestClientResponseException exception) {
			throw new GitlabOAuthException(errorMessage(exception), exception);
		}
		catch (RestClientException exception) {
			throw new GitlabOAuthException("连不上 GitLab", exception);
		}
	}

	private static String errorMessage(RestClientResponseException exception) {
		try {
			Map<?, ?> body = exception.getResponseBodyAs(LinkedHashMap.class);
			if (body != null && body.get("error_description") instanceof String description && !description.isBlank()) {
				return description;
			}
		}
		catch (RuntimeException ignored) {
			// 响应体不是 JSON 时，用状态码说明失败。
		}
		return "GitLab 返回 " + exception.getStatusCode().value();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TokenPayload(@JsonProperty("access_token") String accessToken) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record UserPayload(long id, String username, String name, @JsonProperty("avatar_url") String avatarUrl) {
	}

}
