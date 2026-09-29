package com.lms.integrationmanagement.gateway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

/**
 * The AES-256-GCM mechanics shared by {@link CredentialCipher} (payment
 * gateway secrets) and {@link MediaCredentialCipher} (media provider tokens /
 * API keys). Each caller supplies its own key, so a leaked key only exposes
 * one kind of credential. Output is {@code v1:} + Base64(12-byte IV ||
 * ciphertext+tag). Plaintext is never logged.
 *
 * <p>An optional {@code aad} (additional authenticated data) binds a
 * ciphertext to its context: decrypting with any other {@code aad} fails, so a
 * value copied into another row cannot be opened there.
 */
final class AesGcm {

	private static final String PREFIX = "v1:";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private AesGcm() {
	}

	/** A usable key from a Base64-encoded 32-byte value, or empty when unset/malformed. */
	static Optional<SecretKey> parseKey(String configured) {
		if (!StringUtils.hasText(configured)) {
			return Optional.empty();
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(configured.trim());
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		return (raw.length == 32) ? Optional.of(new SecretKeySpec(raw, "AES")) : Optional.empty();
	}

	static String encrypt(SecretKey key, String plaintext) {
		return encrypt(key, plaintext, null);
	}

	static String encrypt(SecretKey key, String plaintext, String aad) {
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			if (aad != null) {
				cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			}
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
			return PREFIX + Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Credential encryption failed", ex);
		}
	}

	static String decrypt(SecretKey key, String stored) {
		return decrypt(key, stored, null);
	}

	static String decrypt(SecretKey key, String stored, String aad) {
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("Unrecognized credential ciphertext format");
		}
		try {
			byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			if (in.length <= IV_BYTES) {
				throw new IllegalStateException("Credential ciphertext is truncated");
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			if (aad != null) {
				cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			}
			byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Credential decryption failed", ex);
		}
	}

}
