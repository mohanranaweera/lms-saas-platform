package com.lms.integrationmanagement.gateway;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for media/meeting provider credentials (OAuth
 * tokens, DynTube/Bunny API keys) at rest - MEDIA-INT-01 / ADR-018 §2.
 *
 * <p>Uses its own key, {@code MEDIA_CREDENTIALS_ENCRYPTION_KEY}, separate
 * from the payment key. Fails closed exactly like {@link CredentialCipher}:
 * without a valid key every call throws {@link ServiceUnavailableException}
 * (503) and nothing is stored.
 *
 * <p>Every value is bound to its context ({@code aad} - tenant, provider,
 * owner), so a ciphertext copied into another connection row cannot be
 * decrypted there.
 */
@Component
public class MediaCredentialCipher {

	private final MediaIntegrationProperties properties;

	public MediaCredentialCipher(MediaIntegrationProperties properties) {
		this.properties = properties;
	}

	public String encrypt(String plaintext, String aad) {
		return AesGcm.encrypt(key(), plaintext, aad);
	}

	public String decrypt(String stored, String aad) {
		return AesGcm.decrypt(key(), stored, aad);
	}

	public boolean isConfigured() {
		return AesGcm.parseKey(properties.getEncryptionKey()).isPresent();
	}

	private SecretKey key() {
		return AesGcm.parseKey(properties.getEncryptionKey())
			.orElseThrow(() -> new ServiceUnavailableException("Integrations are not available right now."));
	}

}
