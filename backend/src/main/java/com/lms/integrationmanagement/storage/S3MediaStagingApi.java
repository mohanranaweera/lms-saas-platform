package com.lms.integrationmanagement.storage;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.integrationmanagement.config.ObjectStorageProperties;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * {@link MediaStagingApi} on the same S3-compatible bucket as {@link
 * S3ObjectStorageApi} (VIDEO Phase 4), under the {@code video-staging/}
 * prefix. The bucket's lifecycle rule (abort incomplete multipart uploads,
 * expire {@code video-staging/} objects) is the backstop for anything the
 * application's own sweeper misses - see {@code docs/api/video-access-management.md}.
 *
 * <p>Same failure convention as {@link S3ObjectStorageApi}: an SDK exception
 * never leaks; it becomes a client-safe 503.
 */
@Component
@Conditional(ObjectStorageConfiguredCondition.class)
public class S3MediaStagingApi implements MediaStagingApi {

	private static final String UNAVAILABLE_MESSAGE = "Uploads aren't available right now. Please try again later.";

	private final S3Client s3Client;

	private final S3Presigner s3Presigner;

	private final String bucket;

	@Autowired
	public S3MediaStagingApi(ObjectStorageProperties properties) {
		S3Clients.Pair clients = S3Clients.create(properties);
		this.s3Client = clients.client();
		this.s3Presigner = clients.presigner();
		this.bucket = properties.getBucket();
	}

	/** Test-only constructor for mocked SDK clients. */
	S3MediaStagingApi(S3Client s3Client, S3Presigner s3Presigner, String bucket) {
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.bucket = bucket;
	}

	@Override
	public StagingUpload initiate(String key, String contentType) {
		if (key == null || !key.startsWith(KEY_PREFIX) || key.contains("..")) {
			throw new IllegalArgumentException("Not a video staging key");
		}
		try {
			String uploadId = s3Client
				.createMultipartUpload(
						CreateMultipartUploadRequest.builder().bucket(bucket).key(key).contentType(contentType).build())
				.uploadId();
			return new StagingUpload(key, uploadId);
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	@Override
	public List<PresignedPart> presignParts(StagingUpload upload, Collection<Integer> partNumbers, Duration ttl) {
		try {
			Instant expiresAt = Instant.now().plus(ttl);
			return partNumbers.stream().sorted().map(partNumber -> {
				UploadPartRequest part = UploadPartRequest.builder()
					.bucket(bucket)
					.key(upload.objectKey())
					.uploadId(upload.uploadId())
					.partNumber(partNumber)
					.build();
				String url = s3Presigner
					.presignUploadPart(
							UploadPartPresignRequest.builder().signatureDuration(ttl).uploadPartRequest(part).build())
					.url()
					.toString();
				return new PresignedPart(partNumber, url, expiresAt);
			}).toList();
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	@Override
	public void complete(StagingUpload upload, List<CompletedPart> parts) {
		List<software.amazon.awssdk.services.s3.model.CompletedPart> completed = parts.stream()
			.sorted(Comparator.comparingInt(CompletedPart::partNumber))
			.map(part -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
				.partNumber(part.partNumber())
				.eTag(part.etag())
				.build())
			.toList();
		try {
			s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
				.bucket(bucket)
				.key(upload.objectKey())
				.uploadId(upload.uploadId())
				.multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
				.build());
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	@Override
	public void abort(StagingUpload upload) {
		try {
			s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
				.bucket(bucket)
				.key(upload.objectKey())
				.uploadId(upload.uploadId())
				.build());
		}
		catch (NoSuchUploadException e) {
			// Already completed or aborted - nothing left to clean up.
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	@Override
	public long size(String objectKey) {
		try {
			return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build())
				.contentLength();
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	@Override
	public InputStream openRange(String objectKey, long fromInclusive, long toInclusive) {
		try {
			return s3Client.getObject(GetObjectRequest.builder()
				.bucket(bucket)
				.key(objectKey)
				.range("bytes=" + fromInclusive + "-" + toInclusive)
				.build());
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	@Override
	public void delete(String objectKey) {
		try {
			s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
		}
		catch (SdkException e) {
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

}
