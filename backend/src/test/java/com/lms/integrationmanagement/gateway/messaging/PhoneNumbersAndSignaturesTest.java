package com.lms.integrationmanagement.gateway.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** ADR-024 V5 number normalisation and V8 Meta webhook signature verification. */
class PhoneNumbersAndSignaturesTest {

	@Test
	void nationalAndInternationalNumbersBecomeInternationalDigits() {
		assertThat(PhoneNumbers.toInternationalDigits("0771234567", "94")).contains("94771234567");
		assertThat(PhoneNumbers.toInternationalDigits("077-123 4567", "94")).contains("94771234567");
		assertThat(PhoneNumbers.toInternationalDigits("+94 77 123 4567", "94")).contains("94771234567");
		assertThat(PhoneNumbers.toInternationalDigits("0094771234567", "94")).contains("94771234567");
		assertThat(PhoneNumbers.toInternationalDigits("94771234567", "94")).contains("94771234567");
		assertThat(PhoneNumbers.toInternationalDigits("+447911123456", "94")).contains("447911123456");
	}

	@Test
	void unusableNumbersAreRejected() {
		assertThat(PhoneNumbers.toInternationalDigits(null, "94")).isEmpty();
		assertThat(PhoneNumbers.toInternationalDigits("call me", "94")).isEmpty();
		assertThat(PhoneNumbers.toInternationalDigits("12345", "94")).isEmpty();
		assertThat(PhoneNumbers.toInternationalDigits("+1234567890123456", "94")).isEmpty();
		assertThat(PhoneNumbers.toInternationalDigits("0771234567", "")).isEmpty();
	}

	@Test
	void onlyAnHmacOfTheExactBodyWithTheAppSecretVerifies() {
		byte[] body = "{\"entry\":[]}".getBytes(StandardCharsets.UTF_8);
		String signature = "sha256=" + MetaWebhookSignatures.sign("app-secret", body);

		assertThat(MetaWebhookSignatures.verify("app-secret", body, signature)).isTrue();
		assertThat(MetaWebhookSignatures.verify("app-secret", body,
				"sha256=" + MetaWebhookSignatures.sign("app-secret", body).toUpperCase()))
			.as("hex case does not matter")
			.isTrue();
		assertThat(MetaWebhookSignatures.verify("other-secret", body, signature)).isFalse();
		assertThat(MetaWebhookSignatures.verify("app-secret", "{\"entry\":[1]}".getBytes(StandardCharsets.UTF_8),
				signature)).isFalse();
		assertThat(MetaWebhookSignatures.verify("app-secret", body, signature.substring(7))).isFalse();
		assertThat(MetaWebhookSignatures.verify("app-secret", body, null)).isFalse();
		assertThat(MetaWebhookSignatures.verify("", body, signature)).isFalse();
		assertThat(MetaWebhookSignatures.verify(null, body, signature)).isFalse();
	}

}
