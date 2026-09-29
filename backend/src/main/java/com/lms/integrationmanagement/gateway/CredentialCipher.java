package com.lms.integrationmanagement.gateway;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.config.PaymentCredentialProperties;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for gateway credentials at rest (PAY-GW-01 /
 * ADR-017). Output is {@code v1:} + Base64(12-byte IV || ciphertext+tag) -
 * see {@link AesGcm}.
 *
 * <p>Fails closed: with {@code PAYMENT_CREDENTIALS_ENCRYPTION_KEY} unset or
 * not a Base64-encoded 32-byte key, every call throws {@link
 * ServiceUnavailableException} (503) - no credential is ever stored in
 * plaintext or with a weak key. The key is resolved per call (not at
 * construction) so an environment without the feature configured still
 * starts. Plaintext values are never logged.
 */
@Component
public class CredentialCipher {

	private final PaymentCredentialProperties properties;

	public CredentialCipher(PaymentCredentialProperties properties) {
		this.properties = properties;
	}

	public String encrypt(String plaintext) {
		return AesGcm.encrypt(key(), plaintext);
	}

	public String decrypt(String stored) {
		return AesGcm.decrypt(key(), stored);
	}

	/** True when a usable key is configured - lets callers report "not configured" without throwing. */
	public boolean isConfigured() {
		return AesGcm.parseKey(properties.getEncryptionKey()).isPresent();
	}

	private SecretKey key() {
		return AesGcm.parseKey(properties.getEncryptionKey())
			.orElseThrow(() -> new ServiceUnavailableException("Online payment configuration is not available right now."));
	}

}
