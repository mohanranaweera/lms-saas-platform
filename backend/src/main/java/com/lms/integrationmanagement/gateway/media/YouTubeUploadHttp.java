package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.api.VideoProviderUploadApi.ChunkResult;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.Complete;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.Progress;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure.Kind;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadMetadata;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * YouTube Data API v3 resumable upload over HTTP (VIDEO Phase 4, plan §13):
 * open a session, PUT chunks with {@code Content-Range}, resume with
 * {@code bytes *}{@code /total}, cancel with DELETE.
 *
 * <p>Uses the JDK {@link HttpClient} instead of {@link ProviderHttpClient}
 * because the protocol relies on {@code 308} responses with a {@code Range}
 * header and binary request bodies, and a chunk may legitimately take
 * minutes - redirects are never followed.
 *
 * <p>The session URI is validated to point at the configured API origin
 * before any byte is sent to it (it is later used as a request target, so a
 * forged {@code Location} must not become an SSRF). Response bodies are
 * parsed for the id / error reason only and are never logged.
 */
@Component
public class YouTubeUploadHttp {

	private static final Logger log = LoggerFactory.getLogger(YouTubeUploadHttp.class);

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration CHUNK_TIMEOUT = Duration.ofMinutes(5);

	private static final Pattern RANGE = Pattern.compile("bytes=0-(\\d+)");

	/** YouTube "Education" - used only if a video somehow has no category. */
	private static final String DEFAULT_CATEGORY_ID = "27";

	private static final Set<String> SCOPE_REASONS = Set.of("insufficientpermissions", "forbidden");

	private static final Set<String> QUOTA_REASONS = Set.of("quotaexceeded", "uploadlimitexceeded",
			"dailylimitexceeded");

	private static final Set<String> RETRY_REASONS = Set.of("ratelimitexceeded", "userratelimitexceeded",
			"backenderror", "internalerror");

	private final MediaIntegrationProperties.OAuthProvider google;

	private final ObjectMapper objectMapper;

	private final HttpClient http;

	public YouTubeUploadHttp(MediaIntegrationProperties properties, ObjectMapper objectMapper) {
		this.google = properties.getGoogle();
		this.objectMapper = objectMapper;
		this.http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
	}

	/** Opens a resumable session and returns its (validated) session URI. */
	public String initiate(String accessToken, UploadMetadata metadata, long totalBytes, String contentType) {
		Map<String, Object> snippet = new LinkedHashMap<>();
		snippet.put("title", metadata.title());
		if (metadata.description() != null) {
			snippet.put("description", metadata.description());
		}
		Map<String, Object> body = Map.of("snippet", snippet, "status",
				Map.of("privacyStatus", metadata.privacyStatus().toLowerCase(Locale.ROOT), "selfDeclaredMadeForKids",
						false));
		URI uri = URI.create(google.getApiBaseUrl() + "/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status");
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(CONTROL_TIMEOUT)
			.header("Authorization", "Bearer " + accessToken)
			.header("Content-Type", "application/json; charset=UTF-8")
			.header("X-Upload-Content-Length", Long.toString(totalBytes))
			.header("X-Upload-Content-Type", contentType)
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = send(request, uri);
		if (response.statusCode() != 200 && response.statusCode() != 201) {
			throw failureFor(response, uri, false);
		}
		String location = response.headers().firstValue("Location").orElse(null);
		if (location == null || !isApiOrigin(location)) {
			log.atWarn().setMessage("youtube_upload.invalid_session_location").addKeyValue("host", uri.getHost()).log();
			throw new UploadFailure(Kind.RETRYABLE, "YouTube did not accept the upload. It will be retried.");
		}
		return location;
	}

	/** PUTs bytes {@code [offset, offset + length)}. */
	public ChunkResult putChunk(String accessToken, String sessionUri, long offset, byte[] buffer, int length,
			long totalBytes) {
		URI uri = sessionUri(sessionUri);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(CHUNK_TIMEOUT)
			.header("Authorization", "Bearer " + accessToken)
			.header("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + totalBytes)
			.PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, length))
			.build();
		return interpretSessionResponse(send(request, uri), uri);
	}

	/** {@code Content-Range: bytes *}{@code /total} - how much the provider already has. */
	public ChunkResult query(String accessToken, String sessionUri, long totalBytes) {
		URI uri = sessionUri(sessionUri);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(CONTROL_TIMEOUT)
			.header("Authorization", "Bearer " + accessToken)
			.header("Content-Range", "bytes */" + totalBytes)
			.PUT(HttpRequest.BodyPublishers.noBody())
			.build();
		return interpretSessionResponse(send(request, uri), uri);
	}

	/**
	 * VIDEO Phase 5: {@code videos.update} of title / description / privacy.
	 * YouTube resets every writable field of a part that is not sent, so the
	 * current snippet and status are read first and only the three fields are
	 * changed.
	 */
	public void updateVideo(String accessToken, String videoId, UploadMetadata metadata) {
		URI readUri = UriComponentsBuilder.fromUriString(google.getApiBaseUrl() + "/youtube/v3/videos")
			.queryParam("part", "snippet,status")
			.queryParam("id", videoId)
			.encode()
			.build()
			.toUri();
		HttpResponse<String> current = send(HttpRequest.newBuilder(readUri)
			.timeout(CONTROL_TIMEOUT)
			.header("Authorization", "Bearer " + accessToken)
			.GET()
			.build(), readUri);
		if (current.statusCode() != 200) {
			throw failureFor(current, readUri, false);
		}
		JsonNode item = parse(current.body()).path("items").path(0);
		if (item.isMissingNode() || ProviderHttpClient.text(item, "id") == null) {
			throw new UploadFailure(Kind.FATAL, "The video is no longer available on YouTube.");
		}

		Map<String, Object> snippet = new LinkedHashMap<>();
		snippet.put("title", metadata.title());
		snippet.put("description", (metadata.description() == null) ? "" : metadata.description());
		String categoryId = ProviderHttpClient.text(item.path("snippet"), "categoryId");
		snippet.put("categoryId", (categoryId == null) ? DEFAULT_CATEGORY_ID : categoryId);
		copy(item.path("snippet"), snippet, "defaultLanguage", "defaultAudioLanguage");
		if (item.path("snippet").path("tags").isArray()) {
			snippet.put("tags", item.path("snippet").path("tags"));
		}
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("privacyStatus", metadata.privacyStatus().toLowerCase(Locale.ROOT));
		copy(item.path("status"), status, "license", "embeddable", "publicStatsViewable", "selfDeclaredMadeForKids");

		URI updateUri = URI.create(google.getApiBaseUrl() + "/youtube/v3/videos?part=snippet,status");
		Map<String, Object> body = Map.of("id", videoId, "snippet", snippet, "status", status);
		HttpResponse<String> updated = send(HttpRequest.newBuilder(updateUri)
			.timeout(CONTROL_TIMEOUT)
			.header("Authorization", "Bearer " + accessToken)
			.header("Content-Type", "application/json; charset=UTF-8")
			.PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
			.build(), updateUri);
		if (updated.statusCode() == 404) {
			throw new UploadFailure(Kind.FATAL, "The video is no longer available on YouTube.");
		}
		if (updated.statusCode() != 200) {
			throw failureFor(updated, updateUri, false);
		}
	}

	private static void copy(JsonNode from, Map<String, Object> to, String... fields) {
		for (String field : fields) {
			JsonNode value = from.path(field);
			if (value.isValueNode() && !value.isNull()) {
				to.put(field, value);
			}
		}
	}

	/** Best effort: YouTube answers a cancelled session with 499; nothing to do on failure. */
	public void cancel(String accessToken, String sessionUri) {
		try {
			URI uri = sessionUri(sessionUri);
			http.send(HttpRequest.newBuilder(uri)
				.timeout(CONTROL_TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.DELETE()
				.build(), HttpResponse.BodyHandlers.discarding());
		}
		catch (IOException | RuntimeException ex) {
			log.atInfo().setMessage("youtube_upload.cancel_failed").addKeyValue("errorType",
					ex.getClass().getSimpleName()).log();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private ChunkResult interpretSessionResponse(HttpResponse<String> response, URI uri) {
		int code = response.statusCode();
		if (code == 200 || code == 201) {
			String id = videoId(response.body());
			if (id == null) {
				throw new UploadFailure(Kind.RETRYABLE, "YouTube returned an unexpected response. It will be retried.");
			}
			return new Complete(id);
		}
		if (code == 308) {
			String range = response.headers().firstValue("Range").orElse(null);
			if (range == null) {
				return new Progress(0);
			}
			Matcher matcher = RANGE.matcher(range.trim());
			if (!matcher.matches()) {
				throw new UploadFailure(Kind.RETRYABLE, "YouTube returned an unexpected response. It will be retried.");
			}
			return new Progress(Long.parseLong(matcher.group(1)) + 1);
		}
		throw failureFor(response, uri, true);
	}

	private UploadFailure failureFor(HttpResponse<String> response, URI uri, boolean sessionCall) {
		int code = response.statusCode();
		String reason = errorReason(response.body());
		log.atWarn()
			.setMessage("youtube_upload.call_rejected")
			.addKeyValue("host", uri.getHost())
			.addKeyValue("status", code)
			.addKeyValue("reason", reason)
			.log();
		if (code == 401) {
			return new UploadFailure(Kind.AUTH, "YouTube no longer accepts this channel's authorization.");
		}
		if (code == 403 && reason != null && SCOPE_REASONS.contains(reason)) {
			return new UploadFailure(Kind.SCOPE_MISSING, "Reconnect YouTube to allow editing videos.");
		}
		if (sessionCall && (code == 404 || code == 410)) {
			return new UploadFailure(Kind.SESSION_GONE, "The YouTube upload session expired. It will be restarted.");
		}
		if (reason != null && QUOTA_REASONS.contains(reason)) {
			return new UploadFailure(Kind.QUOTA, "The YouTube upload limit for today has been reached.");
		}
		if (code == 429 || code >= 500 || (reason != null && RETRY_REASONS.contains(reason))) {
			return new UploadFailure(Kind.RETRYABLE, "YouTube is temporarily unavailable. It will be retried.");
		}
		return new UploadFailure(Kind.FATAL, "YouTube refused this video.");
	}

	private HttpResponse<String> send(HttpRequest request, URI uri) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			log.atWarn()
				.setMessage("youtube_upload.call_failed")
				.addKeyValue("host", uri.getHost())
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			throw new UploadFailure(Kind.RETRYABLE, "YouTube could not be reached. It will be retried.");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UploadFailure(Kind.RETRYABLE, "The upload was interrupted. It will be retried.");
		}
	}

	private URI sessionUri(String sessionUri) {
		if (!isApiOrigin(sessionUri)) {
			throw new UploadFailure(Kind.SESSION_GONE, "The YouTube upload session is invalid. It will be restarted.");
		}
		return URI.create(sessionUri);
	}

	/** Same scheme, host and port as the configured Google API base URL. */
	boolean isApiOrigin(String candidate) {
		try {
			URI target = URI.create(candidate);
			URI api = URI.create(google.getApiBaseUrl());
			return target.getScheme() != null && target.getScheme().equalsIgnoreCase(api.getScheme())
					&& target.getHost() != null && target.getHost().equalsIgnoreCase(api.getHost())
					&& effectivePort(target) == effectivePort(api) && target.getUserInfo() == null;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return Objects.equals(uri.getScheme(), "https") ? 443 : 80;
	}

	private String videoId(String body) {
		JsonNode json = parse(body);
		return ProviderHttpClient.text(json, "id");
	}

	private String errorReason(String body) {
		JsonNode json = parse(body);
		String reason = ProviderHttpClient.text(json.path("error").path("errors").path(0), "reason");
		return (reason == null) ? null : reason.toLowerCase(Locale.ROOT);
	}

	private JsonNode parse(String body) {
		if (body == null || body.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(body);
		}
		catch (JacksonException ex) {
			return objectMapper.createObjectNode();
		}
	}

}
