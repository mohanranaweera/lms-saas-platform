package com.lms.integrationmanagement.gateway;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.ExamPasswordCipherApi;
import com.lms.integrationmanagement.config.ExamPasswordEncryptionProperties;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * {@link ExamPasswordCipherApi} on the shared {@link AesGcm} mechanics with its own key
 * ({@code exam.password-encryption-key}), mirroring {@link CredentialCipher}: the key is
 * resolved per call so an environment without exams configured still starts, and a
 * missing/malformed key fails closed with 503. Plaintext is never logged.
 */
@Component
public class ExamPasswordCipher implements ExamPasswordCipherApi {

	private static final String AAD_PREFIX = "exam-password:";

	private final ExamPasswordEncryptionProperties properties;

	public ExamPasswordCipher(ExamPasswordEncryptionProperties properties) {
		this.properties = properties;
	}

	@Override
	public String encrypt(String plaintext, String context) {
		return AesGcm.encrypt(key(), plaintext, AAD_PREFIX + context);
	}

	@Override
	public String decrypt(String ciphertext, String context) {
		return AesGcm.decrypt(key(), ciphertext, AAD_PREFIX + context);
	}

	private SecretKey key() {
		return AesGcm.parseKey(properties.getPasswordEncryptionKey())
			.orElseThrow(() -> new ServiceUnavailableException("Exam passwords are not available right now."));
	}

}
