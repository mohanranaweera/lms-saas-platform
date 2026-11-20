package com.lms.integrationmanagement.service;

import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Seals/opens the secret part of a {@code media_provider_connection}: a small
 * JSON document encrypted as one blob ({@code credentials_ciphertext}).
 * Plaintext only ever lives in memory.
 */
@Component
public class MediaCredentialsCodec {

	/**
	 * The secrets for one connection. OAuth providers use the token fields;
	 * DynTube/Bunny use {@code apiKey} (+ Bunny's optional {@code
	 * tokenAuthKey}). {@link #toString()} is redacted.
	 */
	public record StoredCredentials(String accessToken, String refreshToken, String apiKey, String tokenAuthKey) {

		public static StoredCredentials oauth(String accessToken, String refreshToken) {
			return new StoredCredentials(accessToken, refreshToken, null, null);
		}

		public static StoredCredentials apiKey(String apiKey, String tokenAuthKey) {
			return new StoredCredentials(null, null, apiKey, tokenAuthKey);
		}

		@Override
		public String toString() {
			return "StoredCredentials[redacted]";
		}

	}

	/**
	 * What a sealed value belongs to. It is bound into the encryption as
	 * authenticated data, so a ciphertext only opens for the same tenant,
	 * provider and owner.
	 * @param ownerUserId the teacher, or {@code null} for a tenant-level account
	 */
	public record CredentialContext(UUID tenantId, MediaProvider provider, UUID ownerUserId) {

		public static CredentialContext of(MediaProviderConnection connection) {
			return new CredentialContext(connection.getTenantId(), connection.getProvider(),
					connection.getOwnerUserId());
		}

		String aad() {
			return "media-credentials:" + tenantId + ":" + provider.name() + ":"
					+ ((ownerUserId == null) ? "tenant" : ownerUserId.toString());
		}

	}

	private final MediaCredentialCipher cipher;

	private final ObjectMapper objectMapper;

	public MediaCredentialsCodec(MediaCredentialCipher cipher, ObjectMapper objectMapper) {
		this.cipher = cipher;
		this.objectMapper = objectMapper;
	}

	public boolean isConfigured() {
		return cipher.isConfigured();
	}

	public String seal(StoredCredentials credentials, CredentialContext context) {
		return cipher.encrypt(objectMapper.writeValueAsString(credentials), context.aad());
	}

	public StoredCredentials open(String ciphertext, CredentialContext context) {
		return objectMapper.readValue(cipher.decrypt(ciphertext, context.aad()), StoredCredentials.class);
	}

	public StoredCredentials open(MediaProviderConnection connection) {
		return open(connection.getCredentialsCiphertext(), CredentialContext.of(connection));
	}

}
