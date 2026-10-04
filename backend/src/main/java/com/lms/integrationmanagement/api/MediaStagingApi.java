package com.lms.integrationmanagement.api;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Temporary S3 staging for video uploads (VIDEO Phase 4, VID-ADR-004): the
 * browser uploads straight to object storage through presigned multipart
 * part URLs, and the upload worker later reads the object back in bounded
 * ranges to forward it to the video provider. Nothing here is permanent -
 * staging objects are deleted once transferred (and swept otherwise).
 *
 * <p>Object keys are always generated here, under the tenant's own prefix
 * ({@code video-staging/{tenantId}/{assetId}/...}); callers store the key
 * the server returned and never accept one from a client.
 *
 * <p>Every method fails with {@code ServiceUnavailableException} (503) when
 * object storage is not configured or unreachable.
 */
public interface MediaStagingApi {

	/** Prefix of every staging key; the V72 CHECK expects {@code video-staging/{tenantId}/}. */
	String KEY_PREFIX = "video-staging/";

	/**
	 * Starts a multipart upload at {@code objectKey}, which must come from
	 * {@link #newObjectKey} (anything outside {@link #KEY_PREFIX} is refused).
	 */
	StagingUpload initiate(String objectKey, String contentType);

	/** Short-lived PUT URLs for the given part numbers of {@code upload}. */
	List<PresignedPart> presignParts(StagingUpload upload, Collection<Integer> partNumbers, Duration ttl);

	/** Assembles the uploaded parts into the staging object. */
	void complete(StagingUpload upload, List<CompletedPart> parts);

	/** Abandons an unfinished multipart upload (best effort for already-gone uploads). */
	void abort(StagingUpload upload);

	/** Size of the assembled staging object in bytes. */
	long size(String objectKey);

	/**
	 * Bytes {@code [fromInclusive, toInclusive]} of the staging object. The
	 * caller must close the stream.
	 */
	InputStream openRange(String objectKey, long fromInclusive, long toInclusive);

	/** Deletes the staging object (no error if it is already gone). */
	void delete(String objectKey);

	/** The server-held identity of one multipart upload. Never sent to a client. */
	record StagingUpload(String objectKey, String uploadId) {

	}

	record PresignedPart(int partNumber, String url, Instant expiresAt) {

	}

	/** @param etag the ETag the storage returned for the part's PUT */
	record CompletedPart(int partNumber, String etag) {

	}

	/** Builds the tenant-prefixed key for a new staging object. */
	static String newObjectKey(UUID tenantId, UUID assetId) {
		return KEY_PREFIX + tenantId + "/" + assetId + "/" + UUID.randomUUID();
	}

}
