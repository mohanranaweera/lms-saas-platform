package com.lms.integrationmanagement.media;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaStagingApi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VIDEO Phase 4 test double for S3 staging (the backend has no S3
 * Testcontainers module). Presigned part URLs are fake {@code https} URLs;
 * a test plays the browser with {@link #putPart(String, byte[])}, which
 * returns the part's ETag exactly like an S3 PUT would.
 */
public class InMemoryMediaStagingApi implements MediaStagingApi {

	static final String URL_PREFIX = "https://staging.example.test/";

	private record Upload(String key, Map<Integer, byte[]> parts) {

	}

	private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

	private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

	private final List<String> aborted = new CopyOnWriteArrayList<>();

	private final List<String> deleted = new CopyOnWriteArrayList<>();

	private final AtomicInteger counter = new AtomicInteger();

	public void reset() {
		uploads.clear();
		objects.clear();
		aborted.clear();
		deleted.clear();
	}

	@Override
	public StagingUpload initiate(String objectKey, String contentType) {
		if (!objectKey.startsWith(KEY_PREFIX)) {
			throw new IllegalArgumentException("Not a video staging key");
		}
		String uploadId = "up-" + counter.incrementAndGet();
		uploads.put(uploadId, new Upload(objectKey, new ConcurrentHashMap<>()));
		return new StagingUpload(objectKey, uploadId);
	}

	@Override
	public List<PresignedPart> presignParts(StagingUpload upload, Collection<Integer> partNumbers, Duration ttl) {
		Instant expiresAt = Instant.now().plus(ttl);
		return partNumbers.stream()
			.sorted()
			.map(n -> new PresignedPart(n, URL_PREFIX + upload.uploadId() + "/" + n + "?X-Amz-Expires="
					+ ttl.toSeconds(), expiresAt))
			.toList();
	}

	/** The browser's PUT to a presigned part URL. @return the ETag */
	public String putPart(String presignedUrl, byte[] bytes) {
		String[] path = presignedUrl.substring(URL_PREFIX.length()).split("\\?")[0].split("/");
		Upload upload = uploads.get(path[0]);
		if (upload == null) {
			throw new IllegalStateException("No such multipart upload");
		}
		int partNumber = Integer.parseInt(path[1]);
		upload.parts().put(partNumber, bytes.clone());
		return etag(partNumber, bytes);
	}

	@Override
	public void complete(StagingUpload upload, List<CompletedPart> parts) {
		Upload open = uploads.remove(upload.uploadId());
		if (open == null) {
			throw new ServiceUnavailableException("No such multipart upload");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (CompletedPart part : parts.stream().sorted((a, b) -> Integer.compare(a.partNumber(), b.partNumber()))
			.toList()) {
			byte[] bytes = open.parts().get(part.partNumber());
			if (bytes == null || !etag(part.partNumber(), bytes).equals(part.etag())) {
				throw new ServiceUnavailableException("Part does not match");
			}
			out.writeBytes(bytes);
		}
		objects.put(open.key(), out.toByteArray());
	}

	@Override
	public void abort(StagingUpload upload) {
		uploads.remove(upload.uploadId());
		aborted.add(upload.uploadId());
	}

	@Override
	public long size(String objectKey) {
		byte[] bytes = objects.get(objectKey);
		if (bytes == null) {
			throw new ServiceUnavailableException("No such staging object");
		}
		return bytes.length;
	}

	@Override
	public InputStream openRange(String objectKey, long fromInclusive, long toInclusive) {
		byte[] bytes = objects.get(objectKey);
		if (bytes == null) {
			throw new ServiceUnavailableException("No such staging object");
		}
		int to = (int) Math.min(toInclusive + 1, bytes.length);
		return new ByteArrayInputStream(Arrays.copyOfRange(bytes, (int) fromInclusive, to));
	}

	@Override
	public void delete(String objectKey) {
		objects.remove(objectKey);
		deleted.add(objectKey);
	}

	public boolean hasObject(String objectKey) {
		return objects.containsKey(objectKey);
	}

	public Set<String> objectKeys() {
		return Set.copyOf(objects.keySet());
	}

	public List<String> abortedUploads() {
		return List.copyOf(aborted);
	}

	public List<String> deletedKeys() {
		return List.copyOf(deleted);
	}

	private static String etag(int partNumber, byte[] bytes) {
		return "\"etag-" + partNumber + "-" + bytes.length + "-" + Arrays.hashCode(bytes) + "\"";
	}

}
