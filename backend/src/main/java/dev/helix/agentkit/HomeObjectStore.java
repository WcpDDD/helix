package dev.helix.agentkit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import io.agentkit.api.IdentityContext;
import io.agentkit.api.Principal;
import io.agentkit.api.store.PublicUrlIssuer;
import io.agentkit.api.store.Store;
import io.agentkit.api.store.StoreInvalidKeyException;
import io.agentkit.api.store.StoreIoException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HomeObjectStore {

	private static final IdentityContext IDENTITY = new IdentityContext(Principal.service("helix-home"), "helix");

	private static final Duration READABLE_FOR = Duration.ofHours(6);

	private final ObjectStorageProperties properties;

	private volatile Store store;

	public HomeObjectStore(ObjectStorageProperties properties) {
		this.properties = properties;
	}

	public Store openQuietly() {
		if (!properties.ready()) {
			return null;
		}
		try {
			return open();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	public HomeAgentService.HomeFile upload(String name, byte[] body) {
		if (body == null || body.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件是空的。");
		}
		Store opened = require();
		if (!(opened instanceof PublicUrlIssuer issuer)) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储签不出公开地址。");
		}
		String suffix = suffix(name);
		String object = "files/upload/" + UUID.randomUUID() + (suffix.isEmpty() ? "" : "." + suffix);
		String shown = shown(name, suffix);
		try {
			opened.put(IDENTITY, object, new ByteArrayInputStream(body));
			URI url = issuer.issue(IDENTITY, object, READABLE_FOR);
			if (!owned(url)) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "对象地址不在配置的存储上。");
			}
			return new HomeAgentService.HomeFile(shown, url.toString());
		}
		catch (ResponseStatusException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "附件没有传上去。");
		}
	}

	public boolean owned(URI url) {
		if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || !properties.ready()) {
			return false;
		}
		String endpointHost = host(properties.endpoint());
		if (endpointHost == null) {
			return false;
		}
		String actual = url.getHost();
		if (endpointHost.equalsIgnoreCase(actual)) {
			return true;
		}
		String bucket = properties.bucket().strip();
		return (bucket + "." + endpointHost).equalsIgnoreCase(actual);
	}

	private Store require() {
		if (!properties.ready()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储还没配。");
		}
		try {
			return open();
		}
		catch (RuntimeException exception) {
			note(exception);
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储没有连上。");
		}
	}

	private Store open() {
		Store existing = store;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			if (store != null) {
				return store;
			}
			S3AsyncClient client = client();
			S3Presigner presigner = presigner();
			try {
				join(client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket().strip()).build()));
			}
			catch (RuntimeException exception) {
				close(client, presigner);
				throw exception;
			}
			store = new CosStore(client, presigner, properties.bucket().strip(), prefix());
			return store;
		}
	}

	private S3AsyncClient client() {
		return S3AsyncClient.builder()
				.region(Region.of(region()))
				.credentialsProvider(credentials())
				.endpointOverride(endpoint())
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
				.httpClientBuilder(NettyNioAsyncHttpClient.builder())
				.build();
	}

	private S3Presigner presigner() {
		return S3Presigner.builder()
				.region(Region.of(region()))
				.credentialsProvider(credentials())
				.endpointOverride(endpoint())
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
				.build();
	}

	private StaticCredentialsProvider credentials() {
		return StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.accessKey().strip(), properties.secretKey().strip()));
	}

	private URI endpoint() {
		URI uri = URI.create(properties.endpoint().strip());
		if (uri.getScheme() == null || uri.getHost() == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储没有连上。");
		}
		return uri;
	}

	private String region() {
		return properties.region() == null || properties.region().isBlank() ? "ap-shanghai" : properties.region().strip();
	}

	private String prefix() {
		return properties.prefix() == null || properties.prefix().isBlank() ? "helix/home" : properties.prefix().strip();
	}

	private static void note(RuntimeException exception) {
		if (exception instanceof S3Exception s3) {
			String code = s3.awsErrorDetails() == null ? "" : s3.awsErrorDetails().errorCode();
			System.out.println("对象存储没有连上 " + s3.statusCode() + (code == null || code.isBlank() ? "" : " " + code));
			return;
		}
		System.out.println("对象存储没有连上");
	}

	private static void close(S3AsyncClient client, S3Presigner presigner) {
		try {
			presigner.close();
		}
		finally {
			client.close();
		}
	}

	private static <T> T join(CompletableFuture<T> future) {
		try {
			return future.join();
		}
		catch (CompletionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new StoreIoException("对象存储没有连上。", cause);
		}
	}

	private static String host(String endpoint) {
		try {
			return URI.create(endpoint.strip()).getHost();
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String suffix(String name) {
		String file = shown(name, "");
		int dot = file.lastIndexOf('.');
		if (dot < 0 || dot == file.length() - 1) {
			return "";
		}
		String suffix = file.substring(dot + 1).toLowerCase(Locale.ROOT);
		if (!suffix.chars().allMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'))) {
			return "";
		}
		return suffix.length() > 12 ? suffix.substring(0, 12) : suffix;
	}

	private static String shown(String name, String suffix) {
		String file = name == null ? "" : name.strip();
		int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
		if (slash >= 0) {
			file = file.substring(slash + 1);
		}
		if (file.isBlank() || ".".equals(file) || "..".equals(file)) {
			return suffix.isEmpty() ? "附件" : "附件." + suffix;
		}
		return file.length() > 120 ? file.substring(0, 120) : file;
	}

	private static final class CosStore implements Store, PublicUrlIssuer {

		private final S3AsyncClient client;

		private final S3Presigner presigner;

		private final String bucket;

		private final String prefix;

		private CosStore(S3AsyncClient client, S3Presigner presigner, String bucket, String prefix) {
			this.client = client;
			this.presigner = presigner;
			this.bucket = bucket;
			this.prefix = trim(prefix);
		}

		@Override
		public void put(IdentityContext context, String key, InputStream value) {
			String physical = physical(context, key);
			Path temporary;
			try {
				temporary = Files.createTempFile("helix-object-", ".tmp");
			}
			catch (java.io.IOException exception) {
				throw new StoreIoException("附件没有传上去。", exception);
			}
			try {
				try (OutputStream out = Files.newOutputStream(temporary)) {
					value.transferTo(out);
				}
				join(client.putObject(PutObjectRequest.builder().bucket(bucket).key(physical).contentLength(Files.size(temporary)).build(),
						AsyncRequestBody.fromFile(temporary)));
			}
			catch (StoreIoException exception) {
				throw exception;
			}
			catch (java.io.IOException exception) {
				throw new StoreIoException("附件没有传上去。", exception);
			}
			catch (SdkException exception) {
				throw io(exception);
			}
			finally {
				try {
					Files.deleteIfExists(temporary);
				}
				catch (java.io.IOException ignored) {
					// 临时文件留着不影响已经写上的对象
				}
			}
		}

		@Override
		public Optional<InputStream> get(IdentityContext context, String key) {
			try {
				ResponseInputStream<GetObjectResponse> body = join(client.getObject(
						GetObjectRequest.builder().bucket(bucket).key(physical(context, key)).build(),
						AsyncResponseTransformer.toBlockingInputStream()));
				return Optional.of(body);
			}
			catch (S3Exception exception) {
				if (exception.statusCode() == 404) {
					return Optional.empty();
				}
				throw io(exception);
			}
			catch (SdkException exception) {
				throw io(exception);
			}
		}

		@Override
		public void delete(IdentityContext context, String key) {
			try {
				join(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(physical(context, key)).build()));
			}
			catch (SdkException exception) {
				throw io(exception);
			}
		}

		@Override
		public List<String> list(IdentityContext context, String prefix) {
			if (prefix == null) {
				throw new StoreInvalidKeyException("key must be non-blank");
			}
			String isolation = isolation(context);
			String start = isolation + prefix;
			List<String> keys = new ArrayList<>();
			String token = null;
			try {
				do {
					var request = ListObjectsV2Request.builder().bucket(bucket).prefix(start);
					if (token != null && !token.isBlank()) {
						request.continuationToken(token);
					}
					var page = join(client.listObjectsV2(request.build()));
					for (S3Object object : page.contents()) {
						String name = object.key();
						if (name != null && name.startsWith(isolation)) {
							String objectKey = name.substring(isolation.length());
							if (!objectKey.isEmpty() && objectKey.startsWith(prefix)) {
								keys.add(objectKey);
							}
						}
					}
					token = page.nextContinuationToken();
				}
				while (token != null && !token.isBlank());
			}
			catch (SdkException exception) {
				throw io(exception);
			}
			return List.copyOf(keys);
		}

		@Override
		public URI issue(IdentityContext context, String key, Duration validFor) {
			if (validFor == null || validFor.isZero() || validFor.isNegative()) {
				throw new IllegalArgumentException("validFor");
			}
			String physical = physical(context, key);
			try {
				join(client.headObject(HeadObjectRequest.builder().bucket(bucket).key(physical).build()));
				return presigner.presignGetObject(GetObjectPresignRequest.builder()
						.signatureDuration(validFor)
						.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(physical).build())
						.build()).url().toURI();
			}
			catch (S3Exception exception) {
				throw io(exception);
			}
			catch (SdkException exception) {
				throw io(exception);
			}
			catch (java.net.URISyntaxException exception) {
				throw new StoreIoException("对象存储签不出公开地址。", exception);
			}
		}

		@Override
		public void close() {
			HomeObjectStore.close(client, presigner);
		}

		private String physical(IdentityContext context, String key) {
			requireKey(key);
			String physical = isolation(context) + key;
			if (physical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
				throw new StoreInvalidKeyException("S3 key exceeds 1024 bytes");
			}
			return physical;
		}

		private String isolation(IdentityContext context) {
			String tenant = context == null || context.tenantId() == null || context.tenantId().isBlank() ? "_" : context.tenantId().strip();
			requireKey(tenant);
			return prefix.isEmpty() ? tenant + "/" : prefix + "/" + tenant + "/";
		}

		private static void requireKey(String key) {
			if (key == null || key.isBlank() || key.indexOf('\0') >= 0 || key.startsWith("/") || key.endsWith("/")) {
				throw new StoreInvalidKeyException("key must be non-blank");
			}
			for (String part : key.split("/")) {
				if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
					throw new StoreInvalidKeyException("illegal path segment");
				}
			}
		}

		private static String trim(String prefix) {
			String value = prefix == null ? "" : prefix.strip();
			while (value.startsWith("/")) {
				value = value.substring(1);
			}
			while (value.endsWith("/")) {
				value = value.substring(0, value.length() - 1);
			}
			return value;
		}

		private static StoreIoException io(SdkException exception) {
			if (exception instanceof S3Exception s3 && s3.awsErrorDetails() != null && s3.awsErrorDetails().errorCode() != null) {
				return new StoreIoException("对象存储没有连上 " + s3.statusCode() + " " + s3.awsErrorDetails().errorCode(), exception);
			}
			return new StoreIoException("对象存储没有连上。", exception);
		}

	}

}
