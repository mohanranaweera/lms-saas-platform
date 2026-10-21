package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.config.PaymentCredentialProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** AES-256-GCM credential encryption (PAY-GW-01): round trip, tamper detection, fail-closed key handling. */
class CredentialCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString("unit-test-only-key-32-bytes-long".getBytes());

	private static CredentialCipher cipherWithKey(String key) {
		PaymentCredentialProperties properties = new PaymentCredentialProperties();
		properties.setEncryptionKey(key);
		return new CredentialCipher(properties);
	}

	@Test
	void roundTripsAndNeverStoresPlaintext() {
		CredentialCipher cipher = cipherWithKey(KEY);
		String stored = cipher.encrypt("merchant-secret-value");

		assertThat(stored).startsWith("v1:").doesNotContain("merchant-secret-value");
		assertThat(cipher.decrypt(stored)).isEqualTo("merchant-secret-value");
	}

	@Test
	void encryptingTheSameValueTwiceUsesAFreshIv() {
		CredentialCipher cipher = cipherWithKey(KEY);
		assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
	}

	@Test
	void tamperedCiphertextIsRejected() {
		CredentialCipher cipher = cipherWithKey(KEY);
		byte[] raw = Base64.getDecoder().decode(cipher.encrypt("secret").substring(3));
		raw[raw.length - 1] ^= 0x01;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aDifferentKeyCannotDecrypt() {
		String stored = cipherWithKey(KEY).encrypt("secret");
		String otherKey = Base64.getEncoder().encodeToString("another-unit-test-key-32-bytes!!".getBytes());

		assertThatThrownBy(() -> cipherWithKey(otherKey).decrypt(stored)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void missingOrWrongLengthKeyFailsClosedWith503() {
		assertThat(cipherWithKey("").isConfigured()).isFalse();
		assertThatThrownBy(() -> cipherWithKey("").encrypt("x")).isInstanceOf(ServiceUnavailableException.class);
		String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
		assertThatThrownBy(() -> cipherWithKey(shortKey).encrypt("x")).isInstanceOf(ServiceUnavailableException.class);
		assertThat(cipherWithKey(KEY).isConfigured()).isTrue();
	}

}
