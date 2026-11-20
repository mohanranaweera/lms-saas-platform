package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.gateway.media.MediaProviderException.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DynTube's management and upload APIs (VIDEO Phase 13, ADR-026). Only the
 * fixed, configured base URLs are called (no SSRF); every id that goes into
 * a URL path is checked against {@link #ID_PATTERN} first, and values
 * DynTube returns are checked the same way before they are trusted.
 *
 * <p>Provider bodies are never logged or propagated - only the HTTP status.
 * Never call this inside an open DB transaction.
 */
@Component
public class DynTubeClient {

	private static final Logger log = LoggerFactory.getLogger(DynTubeClient.class);

	/** DynTube ids and keys: 22 chars in practice; URL-safe Base64 alphabet. */
	public static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{6,64}$");

	/** DynTube answers some requests without a recognisable client with an empty 200. */
	static final String USER_AGENT = "AOC-LMS/1.0 (+server)";

	private static final int MAX_PROJECT_PAGES = 20;

	private static final Duration UPLOAD_TIMEOUT = Duration.ofHours(2);

	/** {@code GET /videos/{id}} {@code status} values (DynTube "Video Status Codes"). */
	public enum VideoState {

		NOT_AVAILABLE, UPLOADED, PROCESSING, PUBLISHED, FAILED, DELETED, UNKNOWN;

		static VideoState of(int code) {
			return switch (code) {
				case 0 -> NOT_AVAILABLE;
				case 1 -> UPLOADED;
				case 2 -> PROCESSING;
				case 3 -> PUBLISHED;
				case -1 -> FAILED;
				case 50 -> DELETED;
				default -> UNKNOWN;
			};
		}

	}

	public record Project(String id, String name) {

	}

	/**
	 * @param key the playback key used for token URLs; validated, never logged
	 * @param embedKey VIDEO Phase 13c: DynTube's {@code channelKey} for its JS
	 * embed (ADR-026 Amendment 1); validated, {@code null} when absent
	 */
	public record Video(String id, VideoState state, String key, Integer durationSeconds, String thumbnailUrl,
			String embedKey) {

		@Override
		public String toString() {
			return "DynTubeVideo[id=" + id + ", state=" + state + "]";
		}

	}

	private final ProviderHttpClient http;

	private final MediaIntegrationProperties properties;

	private final ObjectMapper objectMapper;

	private final HttpClient uploadClient;

	private final SecureRandom random = new SecureRandom();

	public DynTubeClient(ProviderHttpClient http, MediaIntegrationProperties properties, ObjectMapper objectMapper) {
		this.http = http;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.uploadClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	/** All projects of the account (also the API-key check). */
	public List<Project> listProjects(String apiKey) {
		List<Project> projects = new ArrayList<>();
		int page = 1;
		int totalPages = 1;
		while (page <= totalPages && page <= MAX_PROJECT_PAGES) {
			JsonNode body = http.get(URI.create(apiBase() + "/projects?page=" + page), auth(apiKey));
			JsonNode list = body.path("projects");
			if (!list.isArray()) {
				throw new MediaProviderException(Kind.UNAVAILABLE, "DynTube returned an unexpected response.");
			}
			for (JsonNode node : list) {
				String id = ProviderHttpClient.text(node, "id");
				if (id != null && ID_PATTERN.matcher(id).matches()) {
					projects.add(new Project(id, ProviderHttpClient.text(node, "name")));
				}
			}
			totalPages = Math.max(1, body.path("pager").path("totalPages").asInt(1));
			page++;
		}
		return Collections.unmodifiableList(projects);
	}

	public Video getVideo(String apiKey, String videoId) {
		requireId(videoId);
		JsonNode node = http.get(URI.create(apiBase() + "/videos/" + videoId), auth(apiKey));
		String id = ProviderHttpClient.text(node, "id");
		if (id == null || !id.equals(videoId)) {
			throw new MediaProviderException(Kind.UNAVAILABLE, "DynTube returned an unexpected response.");
		}
		String key = ProviderHttpClient.text(node, "key");
		if (key != null && !ID_PATTERN.matcher(key).matches()) {
			key = null;
		}
		String embedKey = ProviderHttpClient.text(node, "channelKey");
		if (embedKey != null && !ID_PATTERN.matcher(embedKey).matches()) {
			embedKey = null;
		}
		JsonNode status = node.path("status");
		VideoState state = status.isNumber() ? VideoState.of(status.asInt()) : VideoState.UNKNOWN;
		return new Video(id, state, key, parseDuration(ProviderHttpClient.text(node, "duration")),
				httpsOrNull(ProviderHttpClient.text(node.path("image"), "mdUrl")), embedKey);
	}

	/**
	 * {@code POST {upload}/videos} as one streamed multipart request. The
	 * file is never held in memory: {@code content} is read once into the
	 * request body.
	 *
	 * @return DynTube's {@code videoId} for the new video
	 */
	public String upload(String apiKey, String projectId, String title, String description, String fileName,
			String contentType, InputStream content) {
		requireId(projectId);
		String boundary = "----aoc" + HexFormat.of().formatHex(randomBytes());
		StringBuilder head = new StringBuilder();
		field(head, boundary, "projectId", projectId);
		field(head, boundary, "title", title);
		if (description != null) {
			field(head, boundary, "description", description);
		}
		head.append("--").append(boundary).append("\r\n")
			.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
			.append(safeFileName(fileName))
			.append("\"\r\nContent-Type: ")
			.append(contentType)
			.append("\r\n\r\n");
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		InputStream body = new SequenceInputStream(Collections.enumeration(List.of(
				new ByteArrayInputStream(head.toString().getBytes(StandardCharsets.UTF_8)), content,
				new ByteArrayInputStream(tail))));
		URI uri = URI.create(uploadBase() + "/videos");
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(UPLOAD_TIMEOUT)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
			.header(HttpHeaders.USER_AGENT, USER_AGENT)
			.header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofInputStream(() -> body))
			.build();
		HttpResponse<String> response;
		try {
			response = uploadClient.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			log.atWarn().setMessage("media_provider.call_failed").addKeyValue("host", uri.getHost())
				.addKeyValue("errorType", ex.getClass().getSimpleName()).log();
			throw new MediaProviderException(Kind.UNAVAILABLE, "DynTube could not be reached. Try again later.");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new MediaProviderException(Kind.UNAVAILABLE, "The upload was interrupted.");
		}
		int status = response.statusCode();
		if (status / 100 != 2) {
			log.atWarn().setMessage("media_provider.call_rejected").addKeyValue("host", uri.getHost())
				.addKeyValue("status", status).log();
			throw uploadRejected(status);
		}
		String videoId;
		try {
			videoId = ProviderHttpClient.text(objectMapper.readTree(response.body()), "videoId");
		}
		catch (JacksonException ex) {
			videoId = null;
		}
		if (videoId == null || !ID_PATTERN.matcher(videoId).matches()) {
			throw new MediaProviderException(Kind.UNAVAILABLE, "DynTube returned an unexpected response.");
		}
		return videoId;
	}

	/** DynTube {@code duration} {@code "HH:MM:SS.fffffff"} -> whole seconds (rounded up). */
	static Integer parseDuration(String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.split(":");
		if (parts.length != 3) {
			return null;
		}
		try {
			double seconds = Integer.parseInt(parts[0]) * 3600.0 + Integer.parseInt(parts[1]) * 60.0
					+ Double.parseDouble(parts[2]);
			return seconds <= 0 ? null : (int) Math.ceil(seconds);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static MediaProviderException uploadRejected(int status) {
		if (status == 401 || status == 403) {
			return new MediaProviderException(Kind.AUTH_REJECTED, "DynTube rejected the account credentials.");
		}
		if (status == 404) {
			return new MediaProviderException(Kind.NOT_FOUND, "The DynTube project was not found.");
		}
		if (status == 400 || status == 413 || status == 415 || status == 422) {
			return new MediaProviderException(Kind.REJECTED, "DynTube refused this video file.");
		}
		return new MediaProviderException(Kind.UNAVAILABLE, "DynTube could not take the upload. Try again later.");
	}

	private static void field(StringBuilder out, String boundary, String name, String value) {
		out.append("--").append(boundary).append("\r\n")
			.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n")
			.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
			.append(value)
			.append("\r\n");
	}

	/** Keeps the header well-formed whatever the user called the file. */
	static String safeFileName(String fileName) {
		String cleaned = (fileName == null) ? "" : fileName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		return cleaned.isEmpty() ? "video" : cleaned.substring(0, Math.min(cleaned.length(), 120));
	}

	private static String httpsOrNull(String url) {
		return (url != null && url.startsWith("https://") && url.length() <= 1000) ? url : null;
	}

	private static void requireId(String id) {
		if (id == null || !ID_PATTERN.matcher(id).matches()) {
			throw new MediaProviderException(Kind.NOT_FOUND, "The DynTube item was not found.");
		}
	}

	private byte[] randomBytes() {
		byte[] bytes = new byte[12];
		random.nextBytes(bytes);
		return bytes;
	}

	private static Consumer<HttpHeaders> auth(String apiKey) {
		return headers -> {
			headers.setBearerAuth(apiKey);
			headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
		};
	}

	private String apiBase() {
		return properties.getDyntube().getApiBaseUrl();
	}

	private String uploadBase() {
		return properties.getDyntube().getUploadBaseUrl();
	}

}
