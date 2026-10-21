package com.lms.integrationmanagement.gateway.messaging;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the Text.lk and Meta WhatsApp Cloud APIs (MSG-PROV-01
 * tests): one JDK {@link HttpServer} on a random port, no new dependency.
 * Responses are driven by the destination number's last digits:
 *
 * <ul>
 * <li>{@code ...0401}: Text.lk 401 / Meta 401 with error code 190 (auth)</li>
 * <li>{@code ...0429}: Text.lk 429 / Meta 400 with code 130429 (throughput - transient)</li>
 * <li>{@code ...0500}: 503 from either (transient)</li>
 * <li>{@code ...1026}: Text.lk 200 {@code status=error} / Meta 400 code 131026 (permanent)</li>
 * <li>anything else: accepted, with a fresh message id</li>
 * </ul>
 * All tokens are fake test values.
 */
public final class FakeMessagingVendorServer {

	public record Recorded(String method, String path, String authorization, String body) {

	}

	private static final FakeMessagingVendorServer INSTANCE = new FakeMessagingVendorServer();

	private static final Pattern TEXTLK_RECIPIENT = Pattern.compile("\"recipient\"\\s*:\\s*\"(\\d+)\"");

	private static final Pattern META_TO = Pattern.compile("\"to\"\\s*:\\s*\"(\\d+)\"");

	private final HttpServer server;

	private final List<Recorded> requests = new CopyOnWriteArrayList<>();

	private final AtomicInteger counter = new AtomicInteger();

	private FakeMessagingVendorServer() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.createContext("/", this::handle);
		server.start();
	}

	public static FakeMessagingVendorServer get() {
		return INSTANCE;
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public List<Recorded> requests() {
		return List.copyOf(requests);
	}

	/** The newest recorded request whose body contains {@code needle}. */
	public Recorded lastContaining(String needle) {
		List<Recorded> all = requests();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (all.get(i).body().contains(needle)) {
				return all.get(i);
			}
		}
		throw new AssertionError("No vendor request contained " + needle);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body;
		try (InputStream in = exchange.getRequestBody()) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		String path = exchange.getRequestURI().getPath();
		requests.add(new Recorded(exchange.getRequestMethod(), path,
				exchange.getRequestHeaders().getFirst("Authorization"), body));
		if (path.equals("/api/v3/sms/send")) {
			textLk(exchange, digits(TEXTLK_RECIPIENT, body));
		}
		else if (path.endsWith("/messages")) {
			meta(exchange, digits(META_TO, body));
		}
		else {
			respond(exchange, 404, "{}");
		}
	}

	private void textLk(HttpExchange exchange, String to) throws IOException {
		if (to.endsWith("0401")) {
			respond(exchange, 401, "{\"status\":\"error\",\"message\":\"Unauthenticated.\"}");
		}
		else if (to.endsWith("0429")) {
			respond(exchange, 429, "{\"status\":\"error\",\"message\":\"Too many requests\"}");
		}
		else if (to.endsWith("0500")) {
			respond(exchange, 503, "");
		}
		else if (to.endsWith("1026")) {
			respond(exchange, 200, "{\"status\":\"error\",\"message\":\"Invalid recipient " + to + "\"}");
		}
		else {
			respond(exchange, 200, "{\"status\":\"success\",\"message\":\"ok\",\"data\":{\"uid\":\"tlk-"
					+ counter.incrementAndGet() + "\",\"to\":\"" + to + "\",\"status\":\"Delivered\"}}");
		}
	}

	private void meta(HttpExchange exchange, String to) throws IOException {
		if (to.endsWith("0401")) {
			respond(exchange, 401, error(190, "Access token has expired"));
		}
		else if (to.endsWith("0429")) {
			respond(exchange, 400, error(130429, "Rate limit hit"));
		}
		else if (to.endsWith("0500")) {
			respond(exchange, 503, "");
		}
		else if (to.endsWith("1026")) {
			respond(exchange, 400, error(131026, "Message undeliverable"));
		}
		else {
			respond(exchange, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"" + to
					+ "\",\"wa_id\":\"" + to + "\"}],\"messages\":[{\"id\":\"wamid.TEST" + counter.incrementAndGet()
					+ "\"}]}");
		}
	}

	private static String error(int code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
				+ ",\"fbtrace_id\":\"trace\"}}";
	}

	private static String digits(Pattern pattern, String body) {
		Matcher matcher = pattern.matcher(body);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

}
