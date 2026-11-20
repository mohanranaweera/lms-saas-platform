package com.lms.integrationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.integrationmanagement.api.VideoProviderUploadApi.ProcessingState;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.ProviderVideoStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** VIDEO Phase 4 - mapping a YouTube {@code videos.list} item to a processing state. */
class YouTubeUploadServiceStatusTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void aProcessedVideoIsReadyWithDurationAndAnHttpsThumbnail() {
		ProviderVideoStatus status = YouTubeUploadService.toStatus("v1", item("processed", "succeeded", "PT1H2M3S",
				"https://i.ytimg.com/vi/v1/hq.jpg"));

		assertThat(status.state()).isEqualTo(ProcessingState.READY);
		assertThat(status.durationSeconds()).isEqualTo(3723);
		assertThat(status.thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/v1/hq.jpg");
	}

	@Test
	void failedRejectedAndDeletedVideosFailWithAClientSafeReason() {
		assertThat(YouTubeUploadService.toStatus("v", item("failed", "failed", null, null)).state())
			.isEqualTo(ProcessingState.FAILED);
		assertThat(YouTubeUploadService.toStatus("v", item("rejected", null, null, null)).failureReason())
			.isEqualTo("YouTube rejected this video.");
		assertThat(YouTubeUploadService.toStatus("v", item("deleted", null, null, null)).state())
			.isEqualTo(ProcessingState.FAILED);
		assertThat(YouTubeUploadService.toStatus("v", item("uploaded", "terminated", null, null)).state())
			.isEqualTo(ProcessingState.FAILED);
	}

	@Test
	void anUploadedVideoIsStillProcessingAndAnInsecureThumbnailIsDropped() {
		ProviderVideoStatus status = YouTubeUploadService.toStatus("v",
				item("uploaded", "processing", "garbage", "http://insecure.example/x.jpg"));

		assertThat(status.state()).isEqualTo(ProcessingState.PROCESSING);
		assertThat(status.durationSeconds()).isNull();
		assertThat(status.thumbnailUrl()).isNull();
	}

	private JsonNode item(String uploadStatus, String processingStatus, String duration, String thumbnail) {
		String json = "{\"id\":\"v\",\"status\":{\"uploadStatus\":\"" + uploadStatus + "\"}"
				+ ((processingStatus == null) ? ""
						: ",\"processingDetails\":{\"processingStatus\":\"" + processingStatus + "\"}")
				+ ((duration == null) ? "" : ",\"contentDetails\":{\"duration\":\"" + duration + "\"}")
				+ ((thumbnail == null) ? "" : ",\"snippet\":{\"thumbnails\":{\"high\":{\"url\":\"" + thumbnail + "\"}}}")
				+ "}";
		return mapper.readTree(json);
	}

}
