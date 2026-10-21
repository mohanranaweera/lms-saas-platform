package com.lms.integrationmanagement.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * VIDEO Phase 4 - the resumable session URI is used as a request target, so
 * only a URI on the configured Google API origin may ever be followed
 * (no SSRF through a forged {@code Location}).
 */
class YouTubeUploadUnitTest {

	private final YouTubeUploadHttp http = new YouTubeUploadHttp(new MediaIntegrationProperties(),
			JsonMapper.builder().build());

	@Test
	void onlyTheConfiguredApiOriginIsAcceptedAsASessionUri() {
		assertThat(http.isApiOrigin(
				"https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&upload_id=abc")).isTrue();
		assertThat(http.isApiOrigin("https://www.googleapis.com:443/upload/youtube/v3/videos?upload_id=abc"))
			.isTrue();

		assertThat(http.isApiOrigin("http://www.googleapis.com/upload/youtube/v3/videos")).isFalse();
		assertThat(http.isApiOrigin("https://evil.example.com/upload/youtube/v3/videos")).isFalse();
		assertThat(http.isApiOrigin("https://www.googleapis.com.evil.example.com/x")).isFalse();
		assertThat(http.isApiOrigin("https://user@www.googleapis.com/x")).isFalse();
		assertThat(http.isApiOrigin("https://www.googleapis.com:8443/x")).isFalse();
		assertThat(http.isApiOrigin("https://169.254.169.254/latest/meta-data")).isFalse();
		assertThat(http.isApiOrigin("not a uri")).isFalse();
	}

}
