package com.lms.integrationmanagement.media;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The YouTube Data API resumable upload + {@code videos.list} part of {@link
 * FakeMediaProviderServer} (VIDEO Phase 4, plan §32 "FakeYouTubeServer").
 * Sessions keep the bytes they received and the {@code Authorization} of
 * every request, so tests can assert that a tenant's upload used only that
 * tenant's token (T7). Failures are scripted per call.
 */
public final class FakeYouTubeUploads {

	public static final String UPLOAD_PATH = "/upload/youtube/v3/videos";

	public static final String VIDEOS_PATH = "/youtube/v3/videos";

	private static final Pattern CHUNK_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

	/** One scripted error answer: HTTP status + YouTube error reason (may be null). */
	public record Failure(int status, String reason) {

	}

	public static final class Session {

		private final String id;

		private final long total;

		private final ByteArrayOutputStream received = new ByteArrayOutputStream();

		private final List<String> authorizations = new CopyOnWriteArrayList<>();

		private volatile String videoId;

		private volatile boolean cancelled;

		Session(String id, long total) {
			this.id = id;
			this.total = total;
		}

		public String id() {
			return id;
		}

		public long total() {
			return total;
		}

		public synchronized byte[] bytes() {
			return received.toByteArray();
		}

		public List<String> authorizations() {
			return List.copyOf(authorizations);
		}

		public String videoId() {
			return videoId;
		}

		public boolean cancelled() {
			return cancelled;
		}

	}

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final List<Session> sessionOrder = new CopyOnWriteArrayList<>();

	private final Deque<Failure> initFailures = new ConcurrentLinkedDeque<>();

	private final Deque<Failure> chunkFailures = new ConcurrentLinkedDeque<>();

	/** videoId -> uploadStatus (uploaded / processed / failed / rejected). */
	private final Map<String, String> videoStatuses = new ConcurrentHashMap<>();

	private final List<String> listedIds = new CopyOnWriteArrayList<>();

	private final AtomicInteger counter = new AtomicInteger();

	private volatile Failure alwaysFailChunks;

	/** Failures keyed by the 1-based number of the chunk PUT since the last reset. */
	private final Map<Integer, Failure> failOnChunk = new ConcurrentHashMap<>();

	private final AtomicInteger chunkPuts = new AtomicInteger();

	void reset() {
		sessions.clear();
		sessionOrder.clear();
		initFailures.clear();
		chunkFailures.clear();
		videoStatuses.clear();
		listedIds.clear();
		alwaysFailChunks = null;
		failOnChunk.clear();
		chunkPuts.set(0);
	}

	/** The {@code n}-th chunk PUT (1-based, counted across all sessions) answers with this error. */
	public void failChunk(int n, int status, String reason) {
		failOnChunk.put(n, new Failure(status, reason));
	}

	/** The next session start answers with this error. */
	public void failNextInit(int status, String reason) {
		initFailures.add(new Failure(status, reason));
	}

	/** The next chunk PUT (not a progress query) answers with this error. */
	public void failNextChunk(int status, String reason) {
		chunkFailures.add(new Failure(status, reason));
	}

	/** Every chunk PUT answers with this error until {@code null} is set. */
	public void failAllChunks(Failure failure) {
		this.alwaysFailChunks = failure;
	}

	public void setVideoStatus(String videoId, String uploadStatus) {
		videoStatuses.put(videoId, uploadStatus);
	}

	public List<Session> sessions() {
		return List.copyOf(sessionOrder);
	}

	public List<String> listedIds() {
		return List.copyOf(listedIds);
	}

	boolean handles(String path) {
		return path.equals(UPLOAD_PATH) || path.equals(VIDEOS_PATH);
	}

	void handle(HttpExchange exchange, String method, String path, String query, String authorization, byte[] body,
			String baseUrl) throws IOException {
		if (path.equals(VIDEOS_PATH) && method.equals("GET")) {
			listVideos(exchange, query);
			return;
		}
		String uploadId = param(query, "upload_id");
		if (method.equals("POST") && uploadId == null) {
			Failure failure = initFailures.poll();
			if (failure != null) {
				error(exchange, failure);
				return;
			}
			String total = exchange.getRequestHeaders().getFirst("X-Upload-Content-Length");
			Session session = new Session("sess-" + counter.incrementAndGet(), Long.parseLong(total));
			session.authorizations.add(authorization);
			sessions.put(session.id, session);
			sessionOrder.add(session);
			exchange.getResponseHeaders()
				.set("Location", baseUrl + UPLOAD_PATH + "?uploadType=resumable&upload_id=" + session.id);
			respond(exchange, 200, "{}");
			return;
		}
		Session session = (uploadId == null) ? null : sessions.get(uploadId);
		if (session == null || session.cancelled) {
			respond(exchange, 404, "{\"error\":{\"code\":404,\"errors\":[{\"reason\":\"notFound\"}]}}");
			return;
		}
		session.authorizations.add(authorization);
		if (method.equals("DELETE")) {
			session.cancelled = true;
			respond(exchange, 499, null);
			return;
		}
		String contentRange = exchange.getRequestHeaders().getFirst("Content-Range");
		if (contentRange != null && contentRange.startsWith("bytes */")) {
			progress(exchange, session);
			return;
		}
		Failure scripted = failOnChunk.get(chunkPuts.incrementAndGet());
		Failure failure = (alwaysFailChunks != null) ? alwaysFailChunks
				: (scripted != null) ? scripted : chunkFailures.poll();
		if (failure != null) {
			if (failure.status() == 404 || failure.status() == 410) {
				sessions.remove(session.id);
			}
			error(exchange, failure);
			return;
		}
		Matcher matcher = CHUNK_RANGE.matcher(contentRange == null ? "" : contentRange);
		if (!matcher.matches()) {
			respond(exchange, 400, "{\"error\":{\"code\":400,\"errors\":[{\"reason\":\"badContentRange\"}]}}");
			return;
		}
		long from = Long.parseLong(matcher.group(1));
		synchronized (session) {
			if (from != session.received.size()) {
				progress(exchange, session);
				return;
			}
			session.received.writeBytes(body);
		}
		progress(exchange, session);
	}

	private void progress(HttpExchange exchange, Session session) throws IOException {
		long received = session.bytes().length;
		if (received >= session.total) {
			if (session.videoId == null) {
				session.videoId = "yt-video-" + counter.incrementAndGet();
				videoStatuses.putIfAbsent(session.videoId, "uploaded");
			}
			respond(exchange, 200, "{\"id\":\"" + session.videoId + "\",\"status\":{\"uploadStatus\":\"uploaded\"}}");
			return;
		}
		if (received > 0) {
			exchange.getResponseHeaders().set("Range", "bytes=0-" + (received - 1));
		}
		respond(exchange, 308, null);
	}

	private void listVideos(HttpExchange exchange, String query) throws IOException {
		String ids = param(query, "id");
		List<String> items = new ArrayList<>();
		for (String id : (ids == null) ? new String[0] : ids.split(",")) {
			listedIds.add(id);
			String status = videoStatuses.get(id);
			if (status == null) {
				continue;
			}
			items.add("{\"id\":\"" + id + "\",\"status\":{\"uploadStatus\":\"" + status
					+ "\"},\"processingDetails\":{\"processingStatus\":\""
					+ ("processed".equals(status) ? "succeeded" : "processing")
					+ "\"},\"contentDetails\":{\"duration\":\"PT1M5S\"},\"snippet\":{\"thumbnails\":{\"high\":{\"url\":\"https://i.ytimg.example.test/"
					+ id + "/hq.jpg\"}}}}");
		}
		respond(exchange, 200, "{\"items\":[" + String.join(",", items) + "]}");
	}

	private static void error(HttpExchange exchange, Failure failure) throws IOException {
		String reason = (failure.reason() == null) ? "" : "{\"reason\":\"" + failure.reason() + "\"}";
		respond(exchange, failure.status(),
				"{\"error\":{\"code\":" + failure.status() + ",\"errors\":[" + reason + "]}}");
	}

	private static String param(String rawQuery, String name) {
		if (rawQuery == null) {
			return null;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		if (json == null) {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
			return;
		}
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

}
