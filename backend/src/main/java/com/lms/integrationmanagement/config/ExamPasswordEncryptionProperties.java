package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code exam.password-encryption-key} - the AES-256 key (Base64 of 32 bytes) encrypting
 * exam passwords at rest (Klass Exam Module D6). Empty by default in {@code
 * application.yml}: {@code ExamPasswordCipher} then fails closed (503), so creating a
 * password-protected exam is impossible until {@code EXAM_PASSWORD_ENCRYPTION_KEY} is set.
 * Only the local and test profiles carry a clearly-fake default; never a real key in any
 * committed file.
 */
@Component
@ConfigurationProperties(prefix = "exam")
public class ExamPasswordEncryptionProperties {

	private String passwordEncryptionKey = "";

	public String getPasswordEncryptionKey() {
		return passwordEncryptionKey;
	}

	public void setPasswordEncryptionKey(String passwordEncryptionKey) {
		this.passwordEncryptionKey = passwordEncryptionKey;
	}

}
