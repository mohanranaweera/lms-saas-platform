package com.lms.integrationmanagement.media;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DynTube's management + upload API as seen against a real account on
 * 2026-10-10 (ADR-026 live verification): {@code GET /projects} paged as
 * {@code {pager, projects[]}}, {@code POST /videos} (multipart, answers
 * {@code videoId}/{@code videoKey}), {@code GET /videos/{id}} with numeric
 * {@code status} (2 processing, 3 published, -1 failed) and {@code key}.
 * Part of {@link FakeMediaProviderServer}; both API hosts point at it.
 */
public final class FakeDynTube {

	public static final String GOOD_PROJECT = "projDemo0000000000001";

	public static final String OTHER_PROJECT = "projOther000000000002";

	/** A fake video as DynTube holds it. */
	public record Video(String id, String key, String projectId, String title, int status, long bytes) {

		Video withStatus(int newStatus) {
			return new Video(id, key, projectId, title, newStatus, bytes);
		}

	}

	/** One {@code POST /videos} as received. */
	public record Upload(String authorization, String projectId, String title, long fileBytes, String fileName) {

	}

	private final Map<String, Video> videos = new ConcurrentHashMap<>();

	private final List<Upload> uploads = new CopyOnWriteArrayList<>();

	private final AtomicInteger counter = new AtomicInteger();

	/** HTTP status the next uploads answer with (200 = accept). */
	private volatile int uploadStatus = 200;

	/** VIDEO Phase 13c: when true, {@code GET /videos/{id}} leaves out {@code channelKey}. */
	private volatile boolean withholdChannelKey;

	void reset() {
		videos.clear();
		uploads.clear();
		uploadStatus = 200;
		withholdChannelKey = false;
	}

	public void setWithholdChannelKey(boolean withhold) {
		this.withholdChannelKey = withhold;
	}

	/** The JS-embed key this fake reports for a video (DynTube {@code channelKey}). */
	public static String embedKeyOf(String videoId) {
		return "emb" + videoId;
	}

	public void setUploadStatus(int status) {
		this.uploadStatus = status;
	}

	public List<Upload> uploads() {
		return List.copyOf(uploads);
	}

	public Video video(String id) {
		return videos.get(id);
	}

	public void publish(String id) {
		videos.computeIfPresent(id, (k, v) -> v.withStatus(3));
	}

	public void fail(String id) {
		videos.computeIfPresent(id, (k, v) -> v.withStatus(-1));
	}

	public void remove(String id) {
		videos.remove(id);
	}

	static boolean handles(String path) {
		return path.equals("/projects") || path.equals("/videos") || path.matches("/videos/[^/]+");
	}

	/** @return {status, body} */
	Object[] handle(String method, String path, String authorization, byte[] raw) {
		if (!("Bearer " + FakeMediaProviderServer.GOOD_DYNTUBE_KEY).equals(authorization)) {
			return new Object[] { 401, "{\"message\":\"unauthorized\"}" };
		}
		if (method.equals("GET") && path.equals("/projects")) {
			return new Object[] { 200, "{\"pager\":{\"page\":1,\"totalPages\":1,\"totalResults\":2,\"sort\":\"\"},"
					+ "\"projects\":[{\"id\":\"" + GOOD_PROJECT + "\",\"name\":\"Demo - Library\"},{\"id\":\""
					+ OTHER_PROJECT + "\",\"name\":\"Other\"}]}" };
		}
		if (method.equals("POST") && path.equals("/videos")) {
			return upload(authorization, raw);
		}
		if (method.equals("GET") && path.startsWith("/videos/")) {
			Video video = videos.get(path.substring("/videos/".length()));
			if (video == null) {
				return new Object[] { 404, "{\"title\":\"Not Found\",\"status\":404}" };
			}
			return new Object[] { 200, "{\"id\":\"" + video.id() + "\",\"duration\":\"00:01:05.5000000\","
					+ "\"projectId\":\"" + video.projectId() + "\",\"key\":\"" + video.key() + "\","
					+ (withholdChannelKey ? "" : "\"channelKey\":\"" + embedKeyOf(video.id()) + "\",") + "\"status\":"
					+ video.status() + ",\"title\":\"" + video.title() + "\",\"formats\":{\"hls\":true},"
					+ "\"image\":{\"mdUrl\":\"https://images.dyntube.example/" + video.id() + ".jpg\"}}" };
		}
		return new Object[] { 404, "{}" };
	}

	private Object[] upload(String authorization, byte[] raw) {
		String text = new String(raw, StandardCharsets.ISO_8859_1);
		String projectId = field(text, "projectId");
		String title = field(text, "title");
		String fileName = between(text, "filename=\"", "\"");
		int fileStart = text.indexOf("\r\n\r\n", text.indexOf("name=\"file\"")) + 4;
		int fileEnd = text.lastIndexOf("\r\n--");
		long fileBytes = (fileStart > 3 && fileEnd > fileStart) ? fileEnd - fileStart : 0;
		uploads.add(new Upload(authorization, projectId, title, fileBytes, fileName));
		if (uploadStatus != 200) {
			return new Object[] { uploadStatus, "{\"message\":\"rejected\"}" };
		}
		if (!GOOD_PROJECT.equals(projectId) && !OTHER_PROJECT.equals(projectId)) {
			return new Object[] { 404, "{\"message\":\"project not found\"}" };
		}
		int n = counter.incrementAndGet();
		String id = String.format("dtVideo%015d", n);
		String key = String.format("dtKey%017d", n);
		videos.put(id, new Video(id, key, projectId, title, 2, fileBytes));
		return new Object[] { 200, "{\"videoKey\":\"" + key + "\",\"uploadId\":\"u-" + n + "\",\"videoId\":\"" + id
				+ "\",\"channelKey\":\"ch" + n + "\",\"privateLink\":\"p" + n + "\",\"iframeLink\":\"i" + n + "\"}" };
	}

	private static String field(String multipart, String name) {
		int at = multipart.indexOf("name=\"" + name + "\"");
		if (at < 0) {
			return null;
		}
		int start = multipart.indexOf("\r\n\r\n", at) + 4;
		int end = multipart.indexOf("\r\n", start);
		return (start > 3 && end > start)
				? new String(multipart.substring(start, end).getBytes(StandardCharsets.ISO_8859_1),
						StandardCharsets.UTF_8)
				: null;
	}

	private static String between(String text, String from, String to) {
		int start = text.indexOf(from);
		if (start < 0) {
			return null;
		}
		start += from.length();
		int end = text.indexOf(to, start);
		return end < 0 ? null : text.substring(start, end);
	}

}
