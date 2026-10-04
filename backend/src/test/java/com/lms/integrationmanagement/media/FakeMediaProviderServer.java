package com.lms.integrationmanagement.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the Zoom, Google/YouTube, Vimeo, DynTube and Bunny
 * APIs (MEDIA-INT-01 tests) - one JDK {@link HttpServer} on a random port,
 * no new test dependency. Every provider's base URL is pointed here by
 * {@link MediaIntegrationTestSupport}. All tokens/keys are fake test values.
 */
public final class FakeMediaProviderServer {

	public static final String GOOD_DYNTUBE_KEY = "good-dyntube-key";

	/** A MEDIA-INT-01 library-only Stream key: the account API rejects it (401). */
	public static final String GOOD_BUNNY_KEY = "good-bunny-key";

	/** MEDIA-INT-01A account-level keys - two separate fake Bunny accounts. */
	public static final String GOOD_BUNNY_ACCOUNT_KEY = "good-bunny-account-key";

	public static final String OTHER_BUNNY_ACCOUNT_KEY = "other-bunny-account-key";

	/** Every fake library's own API key starts with this - it must never reach a response. */
	public static final String BUNNY_LIBRARY_SECRET_PREFIX = "lib-secret-";

	public static final String GOOD_BUNNY_LIBRARY = "12345";

	public record Recorded(String method, String path, String query, String authorization, String accessKey,
			String body) {

	}

	private static final FakeMediaProviderServer INSTANCE = new FakeMediaProviderServer();

	private final HttpServer server;

	private final List<Recorded> requests = new CopyOnWriteArrayList<>();

	private final AtomicInteger counter = new AtomicInteger();

	/** Zoom {@code users/me} {@code type}: 2 = Licensed, 1 = Basic. */
	private volatile int zoomUserType = 2;

	private volatile String zoomUserId = "zoom-user-0001";

	private volatile boolean rejectRefresh = false;

	/** VIDEO Phase 4: Google answers refresh-token grants with {@code invalid_grant}. */
	private volatile boolean rejectGoogleRefresh = false;

	private final FakeYouTubeUploads youtube = new FakeYouTubeUploads();

	/** A fake Bunny library, owned by one account key. */
	public record FakeBunnyLibrary(long id, String name, long pullZoneId, String ownerKey) {

	}

	private final Map<Long, FakeBunnyLibrary> bunnyLibraries = new ConcurrentHashMap<>();

	private volatile boolean failBunnyCreate = false;

	/** Scopes Google reports as granted (VIDEO Phase 3 sets upload + readonly). */
	public static final String DEFAULT_GOOGLE_SCOPE = "https://www.googleapis.com/auth/youtube.readonly";

	public static final String TENANT_CHANNEL_SCOPES = "https://www.googleapis.com/auth/youtube.upload "
			+ "https://www.googleapis.com/auth/youtube.readonly";

	private volatile String googleScope = DEFAULT_GOOGLE_SCOPE;

	private volatile String youtubeChannelId = "UC-test-channel";

	private volatile String youtubeChannelTitle = "Teacher Channel";

	private FakeMediaProviderServer() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.createContext("/", this::handle);
		server.start();
	}

	public static FakeMediaProviderServer get() {
		return INSTANCE;
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public void reset() {
		requests.clear();
		zoomUserType = 2;
		zoomUserId = "zoom-user-" + counter.incrementAndGet();
		rejectRefresh = false;
		rejectGoogleRefresh = false;
		youtube.reset();
		bunnyLibraries.clear();
		failBunnyCreate = false;
		googleScope = DEFAULT_GOOGLE_SCOPE;
		youtubeChannelId = "UC-test-channel";
		youtubeChannelTitle = "Teacher Channel";
	}

	public void setGoogleScope(String scope) {
		this.googleScope = scope;
	}

	public void setYoutubeChannel(String channelId, String title) {
		this.youtubeChannelId = channelId;
		this.youtubeChannelTitle = title;
	}

	public void setFailBunnyCreate(boolean fail) {
		this.failBunnyCreate = fail;
	}

	/** Puts a library straight into a fake account (e.g. left over from an interrupted attempt). */
	public FakeBunnyLibrary addBunnyLibrary(String ownerKey, String name) {
		long id = 900_000L + counter.incrementAndGet();
		FakeBunnyLibrary library = new FakeBunnyLibrary(id, name, 700_000L + id, ownerKey);
		bunnyLibraries.put(id, library);
		return library;
	}

	public List<FakeBunnyLibrary> bunnyLibraries() {
		return List.copyOf(bunnyLibraries.values());
	}

	public void setZoomUserType(int type) {
		this.zoomUserType = type;
	}

	public void setZoomUserId(String id) {
		this.zoomUserId = id;
	}

	public String zoomUserId() {
		return zoomUserId;
	}

	public void setRejectRefresh(boolean reject) {
		this.rejectRefresh = reject;
	}

	public void setRejectGoogleRefresh(boolean reject) {
		this.rejectGoogleRefresh = reject;
	}

	/** VIDEO Phase 4: YouTube resumable uploads + videos.list. */
	public FakeYouTubeUploads youtube() {
		return youtube;
	}

	public List<Recorded> requests() {
		return List.copyOf(requests);
	}

	public List<Recorded> requests(String method, String pathPrefix) {
		return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getRawQuery();
		byte[] raw;
		try (InputStream in = exchange.getRequestBody()) {
			raw = in.readAllBytes();
		}
		boolean youtubeUpload = youtube.handles(path);
		// Upload chunks are binary: record their size only.
		String body = youtubeUpload ? "<" + raw.length + " bytes>" : new String(raw, StandardCharsets.UTF_8);
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		String accessKey = exchange.getRequestHeaders().getFirst("AccessKey");
		requests.add(new Recorded(method, path, query, authorization, accessKey, body));
		int n = counter.incrementAndGet();

		if (youtubeUpload) {
			youtube.handle(exchange, method, path, query, authorization, raw, baseUrl());
			return;
		}
		if (method.equals("POST") && path.equals("/oauth/token")) {
			if (body.contains("grant_type=refresh_token") && rejectRefresh) {
				respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
				return;
			}
			respond(exchange, 200, "{\"access_token\":\"zoom-at-" + n + "\",\"refresh_token\":\"zoom-rt-" + n
					+ "\",\"expires_in\":3600,\"scope\":\"meeting:write:meeting meeting:read:meeting\"}");
		}
		else if (method.equals("POST") && path.equals("/token")) {
			if (body.contains("grant_type=refresh_token") && rejectGoogleRefresh) {
				respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
				return;
			}
			respond(exchange, 200, "{\"access_token\":\"google-at-" + n + "\",\"refresh_token\":\"google-rt-" + n
					+ "\",\"expires_in\":3599,\"scope\":\"" + googleScope + "\"}");
		}
		else if (method.equals("POST") && path.equals("/oauth/access_token")) {
			respond(exchange, 200, "{\"access_token\":\"vimeo-at-" + n + "\",\"scope\":\"public private\"}");
		}
		else if (method.equals("POST") && (path.equals("/oauth/revoke") || path.equals("/revoke"))) {
			respond(exchange, 200, "{}");
		}
		else if (method.equals("DELETE") && path.equals("/tokens")) {
			respond(exchange, 204, null);
		}
		else if (method.equals("GET") && path.equals("/users/me")) {
			respond(exchange, 200, "{\"id\":\"" + zoomUserId + "\",\"email\":\"zoom.teacher@example.test\",\"type\":"
					+ zoomUserType + "}");
		}
		else if (method.equals("POST") && path.equals("/users/me/meetings")) {
			respond(exchange, 201, "{\"id\":" + (80_000_000_000L + n) + ",\"topic\":\"x\"}");
		}
		else if (method.equals("POST") && path.matches("/meetings/[^/]+/registrants")) {
			respond(exchange, 201, "{\"registrant_id\":\"reg-" + n + "\",\"join_url\":\"https://zoom.example.test/w/"
					+ path.split("/")[2] + "?tk=registrant-" + n + "\"}");
		}
		else if (method.equals("GET") && path.matches("/meetings/[^/]+")) {
			respond(exchange, 200, "{\"id\":" + path.split("/")[2] + ",\"start_url\":\"https://zoom.example.test/s/"
					+ path.split("/")[2] + "?zak=host-" + n + "\"}");
		}
		else if (method.equals("DELETE") && path.matches("/meetings/[^/]+")) {
			respond(exchange, 204, null);
		}
		else if (method.equals("GET") && path.equals("/youtube/v3/channels")) {
			respond(exchange, 200, "{\"items\":[{\"id\":\"" + youtubeChannelId + "\",\"snippet\":{\"title\":\""
					+ youtubeChannelTitle + "\"}}]}");
		}
		else if (method.equals("GET") && path.equals("/me")) {
			respond(exchange, 200, "{\"uri\":\"/users/777\",\"name\":\"Vimeo Teacher\"}");
		}
		else if (method.equals("GET") && path.equals("/projects")) {
			boolean ok = ("Bearer " + GOOD_DYNTUBE_KEY).equals(authorization);
			respond(exchange, ok ? 200 : 401, ok ? "[]" : "{\"message\":\"unauthorized\"}");
		}
		else if (path.equals("/videolibrary") || path.startsWith("/videolibrary/")) {
			handleBunnyAccountApi(exchange, method, path, query, accessKey, body);
		}
		else if (method.equals("GET") && path.matches("/library/[^/]+/videos")) {
			if (!GOOD_BUNNY_KEY.equals(accessKey)) {
				respond(exchange, 401, "{\"Message\":\"unauthorized\"}");
			}
			else if (!path.split("/")[2].equals(GOOD_BUNNY_LIBRARY)) {
				respond(exchange, 404, "{\"Message\":\"not found\"}");
			}
			else {
				respond(exchange, 200, "{\"totalItems\":0,\"items\":[]}");
			}
		}
		else {
			respond(exchange, 404, "{}");
		}
	}

	/** bunny.net account API: list / get / create video libraries, per account key. */
	private void handleBunnyAccountApi(HttpExchange exchange, String method, String path, String query,
			String accessKey, String body) throws IOException {
		if (!GOOD_BUNNY_ACCOUNT_KEY.equals(accessKey) && !OTHER_BUNNY_ACCOUNT_KEY.equals(accessKey)) {
			respond(exchange, 401, "{\"Message\":\"unauthorized\"}");
			return;
		}
		if (method.equals("GET") && path.equals("/videolibrary")) {
			String search = queryParam(query, "search");
			List<String> items = bunnyLibraries.values()
				.stream()
				.filter(library -> library.ownerKey().equals(accessKey))
				.filter(library -> search == null || library.name().contains(search))
				.map(FakeMediaProviderServer::bunnyJson)
				.toList();
			respond(exchange, 200, "{\"Items\":[" + String.join(",", items) + "],\"CurrentPage\":1,\"TotalItems\":"
					+ items.size() + ",\"HasMoreItems\":false}");
		}
		else if (method.equals("POST") && path.equals("/videolibrary")) {
			if (failBunnyCreate) {
				respond(exchange, 500, "{\"Message\":\"internal error\"}");
				return;
			}
			Matcher name = Pattern.compile("\"Name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
			if (!name.find()) {
				respond(exchange, 400, "{\"Message\":\"Name is required\"}");
				return;
			}
			respond(exchange, 201, bunnyJson(addBunnyLibrary(accessKey, name.group(1))));
		}
		else if (method.equals("GET") && path.matches("/videolibrary/\\d+")) {
			FakeBunnyLibrary library = bunnyLibraries.get(Long.parseLong(path.substring("/videolibrary/".length())));
			if (library == null || !library.ownerKey().equals(accessKey)) {
				respond(exchange, 404, "{\"Message\":\"not found\"}");
			}
			else {
				respond(exchange, 200, bunnyJson(library));
			}
		}
		else {
			respond(exchange, 404, "{}");
		}
	}

	private static String bunnyJson(FakeBunnyLibrary library) {
		return "{\"Id\":" + library.id() + ",\"Name\":\"" + library.name().replace("\"", "\\\"") + "\",\"PullZoneId\":"
				+ library.pullZoneId() + ",\"ApiKey\":\"" + BUNNY_LIBRARY_SECRET_PREFIX + library.id()
				+ "\",\"ReadOnlyApiKey\":\"" + BUNNY_LIBRARY_SECRET_PREFIX + "ro-" + library.id() + "\"}";
	}

	private static String queryParam(String rawQuery, String name) {
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
