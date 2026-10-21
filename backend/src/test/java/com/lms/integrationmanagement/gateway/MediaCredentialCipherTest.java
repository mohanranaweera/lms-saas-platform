package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.config.PaymentCredentialProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * MEDIA-INT-01 / ADR-018 §2: media credentials are encrypted with their own
 * key, fail closed without one, and cannot be opened with the payment key.
 */
class MediaCredentialCipherTest {

	private static final String MEDIA_KEY = Base64.getEncoder()
		.encodeToString("test-only-media-cred-key-32bytes".getBytes());

	private static final String PAYMENT_KEY = Base64.getEncoder()
		.encodeToString("test-only-payment-cred-key-32byt".getBytes());

	private static final String AAD = "media-credentials:tenant-a:ZOOM:teacher-1";

	private static MediaCredentialCipher mediaCipher(String key) {
		MediaIntegrationProperties properties = new MediaIntegrationProperties();
		properties.setEncryptionKey(key);
		return new MediaCredentialCipher(properties);
	}

	private static CredentialCipher paymentCipher(String key) {
		PaymentCredentialProperties properties = new PaymentCredentialProperties();
		properties.setEncryptionKey(key);
		return new CredentialCipher(properties);
	}

	@Test
	void roundTripsAndNeverEmitsThePlaintext() {
		MediaCredentialCipher cipher = mediaCipher(MEDIA_KEY);

		String sealed = cipher.encrypt("{\"accessToken\":\"secret-token\"}", AAD);

		assertThat(sealed).startsWith("v1:").doesNotContain("secret-token");
		assertThat(cipher.decrypt(sealed, AAD)).isEqualTo("{\"accessToken\":\"secret-token\"}");
		assertThat(cipher.encrypt("same", AAD)).isNotEqualTo(cipher.encrypt("same", AAD));
	}

	@Test
	void failsClosedWithoutAValidKey() {
		assertThat(mediaCipher("").isConfigured()).isFalse();
		assertThatThrownBy(() -> mediaCipher("").encrypt("x", AAD)).isInstanceOf(ServiceUnavailableException.class);
		assertThatThrownBy(() -> mediaCipher(Base64.getEncoder().encodeToString(new byte[16])).encrypt("x", AAD))
			.isInstanceOf(ServiceUnavailableException.class);
		assertThatThrownBy(() -> mediaCipher("not base64 !!").encrypt("x", AAD))
			.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void mediaAndPaymentKeysAreSeparate() {
		String sealedWithMedia = mediaCipher(MEDIA_KEY).encrypt("media-secret", AAD);

		assertThatThrownBy(() -> paymentCipher(PAYMENT_KEY).decrypt(sealedWithMedia))
			.isInstanceOf(IllegalStateException.class);
		assertThat(paymentCipher(PAYMENT_KEY).decrypt(paymentCipher(PAYMENT_KEY).encrypt("pay")))
			.isEqualTo("pay");
	}

	@Test
	void rejectsTamperedCiphertext() {
		MediaCredentialCipher cipher = mediaCipher(MEDIA_KEY);
		String sealed = cipher.encrypt("value", AAD);
		String tampered = sealed.substring(0, sealed.length() - 2) + (sealed.endsWith("A") ? "BB" : "AA");

		assertThatThrownBy(() -> cipher.decrypt(tampered, AAD)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aValueCopiedToAnotherTenantOrOwnerCannotBeDecrypted() {
		MediaCredentialCipher cipher = mediaCipher(MEDIA_KEY);
		String sealed = cipher.encrypt("refresh-token", AAD);

		assertThatThrownBy(() -> cipher.decrypt(sealed, "media-credentials:tenant-b:ZOOM:teacher-1"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt(sealed, "media-credentials:tenant-a:ZOOM:teacher-2"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(cipher.decrypt(sealed, AAD)).isEqualTo("refresh-token");
	}

}
