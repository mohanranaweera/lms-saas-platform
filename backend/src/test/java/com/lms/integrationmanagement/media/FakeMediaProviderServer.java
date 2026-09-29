package com.lms.integrationmanagement.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for the Zoom, Google/YouTube, Vimeo, DynTube and Bunny
 * APIs (MEDIA-INT-01 tests) - one JDK {@link HttpServer} on a random port,
 * no new test dependency. Every provider's base URL is pointed here by
 * {@link MediaIntegrationTestSupport}. All tokens/keys are fake test values.
 */
public final class FakeMediaProviderServer {

	public static final String GOOD_DYNTUBE_KEY = "good-dyntube-key";

	public static final String GOOD_BUNNY_KEY = "good-bunny-key";

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
		String body;
		try (InputStream in = exchange.getRequestBody()) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		String accessKey = exchange.getRequestHeaders().getFirst("AccessKey");
		requests.add(new Recorded(method, path, query, authorization, accessKey, body));
		int n = counter.incrementAndGet();

		if (method.equals("POST") && path.equals("/oauth/token")) {
			if (body.contains("grant_type=refresh_token") && rejectRefresh) {
				respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
				return;
			}
			respond(exchange, 200, "{\"access_token\":\"zoom-at-" + n + "\",\"refresh_token\":\"zoom-rt-" + n
					+ "\",\"expires_in\":3600,\"scope\":\"meeting:write:meeting meeting:read:meeting\"}");
		}
		else if (method.equals("POST") && path.equals("/token")) {
			respond(exchange, 200, "{\"access_token\":\"google-at-" + n + "\",\"refresh_token\":\"google-rt-" + n
					+ "\",\"expires_in\":3599,\"scope\":\"https://www.googleapis.com/auth/youtube.readonly\"}");
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
			respond(exchange, 200, "{\"items\":[{\"id\":\"UC-test-channel\",\"snippet\":{\"title\":\"Teacher Channel\"}}]}");
		}
		else if (method.equals("GET") && path.equals("/me")) {
			respond(exchange, 200, "{\"uri\":\"/users/777\",\"name\":\"Vimeo Teacher\"}");
		}
		else if (method.equals("GET") && path.equals("/projects")) {
			boolean ok = ("Bearer " + GOOD_DYNTUBE_KEY).equals(authorization);
			respond(exchange, ok ? 200 : 401, ok ? "[]" : "{\"message\":\"unauthorized\"}");
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
