package com.lms.integrationmanagement.gateway;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.config.PaymentCredentialProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AES-256-GCM encryption for gateway credentials at rest (PAY-GW-01 /
 * ADR-017). Output is {@code v1:} + Base64(12-byte IV || ciphertext+tag).
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

	private static final String PREFIX = "v1:";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final PaymentCredentialProperties properties;

	public CredentialCipher(PaymentCredentialProperties properties) {
		this.properties = properties;
	}

	public String encrypt(String plaintext) {
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
			return PREFIX + Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Credential encryption failed", ex);
		}
	}

	public String decrypt(String stored) {
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("Unrecognized credential ciphertext format");
		}
		try {
			byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			if (in.length <= IV_BYTES) {
				throw new IllegalStateException("Credential ciphertext is truncated");
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Credential decryption failed", ex);
		}
	}

	/** True when a usable key is configured - lets callers report "not configured" without throwing. */
	public boolean isConfigured() {
		try {
			key();
			return true;
		}
		catch (ServiceUnavailableException ex) {
			return false;
		}
	}

	private SecretKey key() {
		String configured = properties.getEncryptionKey();
		if (!StringUtils.hasText(configured)) {
			throw new ServiceUnavailableException("Online payment configuration is not available right now.");
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(configured.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new ServiceUnavailableException("Online payment configuration is not available right now.");
		}
		if (raw.length != 32) {
			throw new ServiceUnavailableException("Online payment configuration is not available right now.");
		}
		return new SecretKeySpec(raw, "AES");
	}

}
