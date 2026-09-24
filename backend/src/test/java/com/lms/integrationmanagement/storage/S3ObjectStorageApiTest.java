package com.lms.integrationmanagement.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Mockito-only unit coverage for {@link S3ObjectStorageApi}: proves each
 * method calls the AWS SDK v2 client/presigner with the expected
 * bucket/key/TTL shape, and that a raw SDK failure never leaks past this
 * adapter unwrapped (translated to {@link ServiceUnavailableException} per
 * this class's own javadoc). No network call, no Testcontainers - a full
 * MinIO round-trip is a nice-to-have but this backend has no generic
 * S3-compatible Testcontainers module wired up yet (only
 * {@code testcontainers-postgresql} is a dependency), so a mocked unit test
 * is the required minimum per the wave-05 plan's own test-plan wording.
 */
@ExtendWith(MockitoExtension.class)
class S3ObjectStorageApiTest {

	private static final String BUCKET = "lms-test-bucket";

	@Mock
	private S3Client s3Client;

	@Mock
	private S3Presigner s3Presigner;

	private S3ObjectStorageApi api;

	@BeforeEach
	void setUp() {
		api = new S3ObjectStorageApi(s3Client, s3Presigner, BUCKET);
	}

	@Test
	void storeUploadsToConfiguredBucketAndReturnsAGeneratedKey() {
		UUID tenantId = UUID.randomUUID();
		byte[] content = "hello world".getBytes();
		StoreObjectCommand command = new StoreObjectCommand(tenantId, new ByteArrayInputStream(content),
				"text/plain", content.length, "hello.txt");

		StoredObject stored = api.store(command);

		ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
		PutObjectRequest request = requestCaptor.getValue();
		assertThat(request.bucket()).isEqualTo(BUCKET);
		assertThat(request.key()).isEqualTo(stored.objectKey());
		assertThat(request.key()).startsWith(tenantId + "/");
		assertThat(request.contentType()).isEqualTo("text/plain");
		assertThat(stored.sizeBytes()).isEqualTo(content.length);
	}

	@Test
	void storeWrapsASdkFailureAsServiceUnavailable() {
		StoreObjectCommand command = new StoreObjectCommand(UUID.randomUUID(), new ByteArrayInputStream(new byte[0]),
				"text/plain", 0, "empty.txt");
		when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
			.thenThrow(SdkClientException.create("boom"));

		assertThatThrownBy(() -> api.store(command)).isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void deleteRemovesTheGivenKeyFromTheConfiguredBucket() {
		String key = "tenant/" + UUID.randomUUID();

		api.delete(key);

		ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client).deleteObject(requestCaptor.capture());
		assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
		assertThat(requestCaptor.getValue().key()).isEqualTo(key);
	}

	@Test
	void deleteWrapsASdkFailureAsServiceUnavailable() {
		when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
			.thenThrow(AwsServiceException.builder().message("boom").build());

		assertThatThrownBy(() -> api.delete("some-key")).isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void generateSignedDownloadUrlPresignsAGetRequestScopedToTheKeyAndTtl() throws Exception {
		String key = "tenant/" + UUID.randomUUID();
		Duration ttl = Duration.ofMinutes(5);
		PresignedGetObjectRequest presigned = mockPresignedRequest("https://minio.test/" + BUCKET + "/" + key);
		when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

		SignedDownloadUrl signed = api.generateSignedDownloadUrl(key, ttl);

		ArgumentCaptor<GetObjectPresignRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
		verify(s3Presigner).presignGetObject(requestCaptor.capture());
		GetObjectPresignRequest presignRequest = requestCaptor.getValue();
		assertThat(presignRequest.signatureDuration()).isEqualTo(ttl);
		GetObjectRequest getObjectRequest = (GetObjectRequest) presignRequest.getObjectRequest();
		assertThat(getObjectRequest.bucket()).isEqualTo(BUCKET);
		assertThat(getObjectRequest.key()).isEqualTo(key);
		assertThat(signed.url()).isEqualTo("https://minio.test/" + BUCKET + "/" + key);
		assertThat(signed.expiresAt()).isAfter(Instant.now());
	}

	@Test
	void generateSignedDownloadUrlWrapsASdkFailureAsServiceUnavailable() {
		when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
			.thenThrow(SdkClientException.create("boom"));

		assertThatThrownBy(() -> api.generateSignedDownloadUrl("some-key", Duration.ofMinutes(5)))
			.isInstanceOf(ServiceUnavailableException.class);
	}

	private PresignedGetObjectRequest mockPresignedRequest(String url) throws Exception {
		PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
		when(presigned.url()).thenReturn(URI.create(url).toURL());
		return presigned;
	}

}
