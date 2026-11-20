package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code payment.credentials.*} - the AES-256 key (Base64, 32 bytes) that
 * encrypts tenant gateway credentials at rest (PAY-GW-01). Empty by default:
 * {@code CredentialCipher} then fails closed. Set via
 * {@code PAYMENT_CREDENTIALS_ENCRYPTION_KEY}; never a real key in any
 * committed file.
 */
@Component
@ConfigurationProperties(prefix = "payment.credentials")
public class PaymentCredentialProperties {

	private String encryptionKey = "";

	public String getEncryptionKey() {
		return encryptionKey;
	}

	public void setEncryptionKey(String encryptionKey) {
		this.encryptionKey = encryptionKey;
	}

}
