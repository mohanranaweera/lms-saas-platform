package com.lms.integrationmanagement.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.integrationmanagement.api.MediaStagingApi.CompletedPart;
import com.lms.integrationmanagement.api.MediaStagingApi.PresignedPart;
import com.lms.integrationmanagement.api.MediaStagingApi.StagingUpload;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * VIDEO Phase 4 - {@link S3MediaStagingApi} against mocked SDK clients (no
 * S3 container, same approach as {@code S3ObjectStorageApiTest}).
 */
@ExtendWith(MockitoExtension.class)
class S3MediaStagingApiTest {

	private static final String BUCKET = "test-bucket";

	@Mock
	private S3Client s3Client;

	@Mock
	private S3Presigner s3Presigner;

	private S3MediaStagingApi api;

	@BeforeEach
	void setUp() {
		api = new S3MediaStagingApi(s3Client, s3Presigner, BUCKET);
	}

	@Test
	void initiateStartsAMultipartUploadAtTheServerGeneratedKey() {
		UUID tenantId = UUID.randomUUID();
		String key = MediaStagingApi.newObjectKey(tenantId, UUID.randomUUID());
		when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
			.thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());

		StagingUpload upload = api.initiate(key, "video/mp4");

		ArgumentCaptor<CreateMultipartUploadRequest> request = ArgumentCaptor
			.forClass(CreateMultipartUploadRequest.class);
		verify(s3Client).createMultipartUpload(request.capture());
		assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
		assertThat(request.getValue().key()).isEqualTo(key).startsWith("video-staging/" + tenantId + "/");
		assertThat(request.getValue().contentType()).isEqualTo("video/mp4");
		assertThat(upload).isEqualTo(new StagingUpload(key, "upload-1"));
	}

	@Test
	void initiateRefusesKeysOutsideTheStagingPrefix() {
		assertThatThrownBy(() -> api.initiate("other-tenant/secret", "video/mp4"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> api.initiate("video-staging/../x", "video/mp4"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void presignPartsSignsEachPartForTheGivenTtl() throws Exception {
		PresignedUploadPartRequest presigned = org.mockito.Mockito.mock(PresignedUploadPartRequest.class);
		when(presigned.url()).thenReturn(URI.create("https://s3.example.test/part").toURL());
		when(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class))).thenReturn(presigned);

		List<PresignedPart> parts = api.presignParts(new StagingUpload("video-staging/t/a/k", "u-1"), List.of(2, 1),
				Duration.ofMinutes(15));

		assertThat(parts).extracting(PresignedPart::partNumber).containsExactly(1, 2);
		ArgumentCaptor<UploadPartPresignRequest> request = ArgumentCaptor.forClass(UploadPartPresignRequest.class);
		verify(s3Presigner, org.mockito.Mockito.times(2)).presignUploadPart(request.capture());
		assertThat(request.getAllValues()).allSatisfy(r -> {
			assertThat(r.signatureDuration()).isEqualTo(Duration.ofMinutes(15));
			assertThat(r.uploadPartRequest().uploadId()).isEqualTo("u-1");
			assertThat(r.uploadPartRequest().key()).isEqualTo("video-staging/t/a/k");
		});
	}

	@Test
	void completeSendsThePartsInOrder() {
		api.complete(new StagingUpload("video-staging/t/a/k", "u-1"),
				List.of(new CompletedPart(2, "e2"), new CompletedPart(1, "e1")));

		ArgumentCaptor<CompleteMultipartUploadRequest> request = ArgumentCaptor
			.forClass(CompleteMultipartUploadRequest.class);
		verify(s3Client).completeMultipartUpload(request.capture());
		assertThat(request.getValue().multipartUpload().parts())
			.extracting(software.amazon.awssdk.services.s3.model.CompletedPart::partNumber)
			.containsExactly(1, 2);
	}

	@Test
	void abortIgnoresAnUploadThatIsAlreadyGone() {
		when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
			.thenThrow(NoSuchUploadException.builder().message("gone").build());

		api.abort(new StagingUpload("video-staging/t/a/k", "u-1"));
	}

	@Test
	void openRangeRequestsOnlyTheGivenBytes() {
		api.openRange("video-staging/t/a/k", 10, 19);

		ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3Client).getObject(request.capture());
		assertThat(request.getValue().range()).isEqualTo("bytes=10-19");
	}

	@Test
	void sdkFailuresBecomeServiceUnavailable() {
		when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
			.thenThrow(SdkClientException.create("boom"));

		assertThatThrownBy(() -> api.initiate("video-staging/t/a/k", "video/mp4"))
			.isInstanceOf(ServiceUnavailableException.class);
	}

}
