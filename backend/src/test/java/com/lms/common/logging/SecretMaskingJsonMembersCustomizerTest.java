package com.lms.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** MEDIA-INT-01 security review S7: secrets never reach the JSON log intact. */
class SecretMaskingJsonMembersCustomizerTest {

	@Test
	void masksSecretFragmentsInsideAnyString() {
		assertThat(SecretMaskingJsonMembersCustomizer
			.mask("POST failed: grant_type=refresh_token&refresh_token=abc.def-123&client_secret=s3cr3t"))
			.doesNotContain("abc.def-123")
			.doesNotContain("s3cr3t")
			.contains("refresh_token=***")
			.contains("client_secret=***");
		assertThat(SecretMaskingJsonMembersCustomizer.mask("{\"access_token\":\"zoom-at-1\",\"expires_in\":3600}"))
			.doesNotContain("zoom-at-1")
			.contains("\"expires_in\":3600");
		assertThat(SecretMaskingJsonMembersCustomizer.mask("Authorization: Bearer eyJhbGciOi.x.y"))
			.isEqualTo("Authorization: Bearer ***");
		assertThat(SecretMaskingJsonMembersCustomizer.mask("apiKey=good-bunny-key")).isEqualTo("apiKey=***");
	}

	@Test
	void leavesOrdinaryTextAlone() {
		String message = "media_integration.oauth_connect_failed for provider ZOOM";
		assertThat(SecretMaskingJsonMembersCustomizer.mask(message)).isEqualTo(message);
		assertThat(SecretMaskingJsonMembersCustomizer.mask("")).isEmpty();
		assertThat(SecretMaskingJsonMembersCustomizer.mask(null)).isNull();
	}

	@Test
	void recognisesSecretKeyNamesInAnySpelling() {
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey("accessToken")).isTrue();
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey("refresh_token")).isTrue();
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey("X-Api-Key")).isTrue();
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey("zoomWebhookSecret")).isTrue();
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey("tenantId")).isFalse();
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey("provider")).isFalse();
		assertThat(SecretMaskingJsonMembersCustomizer.isSecretKey(null)).isFalse();
	}

}
