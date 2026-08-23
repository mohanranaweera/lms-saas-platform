package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for {@link WebhookSignatureVerifier} against fixture
 * payloads - valid, tampered, and missing signature - written against this
 * module's own generic adapter contract, not a vendor SDK.
 */
class WebhookSignatureVerifierTest {

	private static final String SECRET = "test-only-webhook-secret-not-for-production-use";

	private static final String BODY = "{\"gatewayReference\":\"FAKE-abc123\",\"confirmed\":true}";

	@Test
	void aCorrectlySignedBodyVerifiesSuccessfully() {
		String signature = WebhookSignatureVerifier.sign(BODY, SECRET);

		assertThat(WebhookSignatureVerifier.verify(BODY, signature, SECRET)).isTrue();
	}

	@Test
	void aTamperedBodyFailsVerificationEvenWithTheOriginalSignature() {
		String signature = WebhookSignatureVerifier.sign(BODY, SECRET);
		String tamperedBody = "{\"gatewayReference\":\"FAKE-abc123\",\"confirmed\":false}";

		assertThat(WebhookSignatureVerifier.verify(tamperedBody, signature, SECRET)).isFalse();
	}

	@Test
	void aSignatureComputedWithTheWrongSecretFailsVerification() {
		String signature = WebhookSignatureVerifier.sign(BODY, "a-completely-different-secret");

		assertThat(WebhookSignatureVerifier.verify(BODY, signature, SECRET)).isFalse();
	}

	@Test
	void aMissingSignatureHeaderFailsVerificationRatherThanThrowing() {
		assertThat(WebhookSignatureVerifier.verify(BODY, null, SECRET)).isFalse();
	}

	@Test
	void aBlankSignatureHeaderFailsVerification() {
		assertThat(WebhookSignatureVerifier.verify(BODY, "   ", SECRET)).isFalse();
	}

	@Test
	void aMalformedNonHexSignatureFailsVerificationRatherThanThrowing() {
		assertThat(WebhookSignatureVerifier.verify(BODY, "not-a-hex-string!!", SECRET)).isFalse();
	}

	@Test
	void anUnconfiguredBlankSecretFailsClosedRegardlessOfSignature() {
		String signature = WebhookSignatureVerifier.sign(BODY, SECRET);

		assertThat(WebhookSignatureVerifier.verify(BODY, signature, "")).isFalse();
	}

}
