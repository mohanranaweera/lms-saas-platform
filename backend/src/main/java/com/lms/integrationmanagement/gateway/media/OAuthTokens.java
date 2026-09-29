package com.lms.integrationmanagement.gateway.media;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Tokens returned by a provider's OAuth token endpoint. Plaintext - held only
 * in memory and encrypted before persistence; never logged ({@link
 * #toString()} is redacted).
 *
 * @param expiresAt {@code null} for tokens that do not expire (Vimeo)
 * @param refreshToken {@code null} when the provider issues none (Vimeo), or
 * did not rotate it on refresh (Google) - the caller keeps the old one
 */
public record OAuthTokens(String accessToken, String refreshToken, Instant expiresAt, String scope) {

	/**
	 * Parses a standard RFC 6749 token response ({@code access_token},
	 * {@code refresh_token}, {@code expires_in}, {@code scope}).
	 * @param previousRefreshToken kept when the response carries no new one
	 */
	static OAuthTokens fromTokenResponse(JsonNode json, String previousRefreshToken, Instant now) {
		String accessToken = ProviderHttpClient.text(json, "access_token");
		if (accessToken == null) {
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"The provider returned an unexpected response.");
		}
		String refreshToken = ProviderHttpClient.text(json, "refresh_token");
		String expiresIn = ProviderHttpClient.text(json, "expires_in");
		Instant expiresAt = null;
		if (expiresIn != null) {
			try {
				expiresAt = now.plusSeconds(Long.parseLong(expiresIn));
			}
			catch (NumberFormatException ex) {
				expiresAt = null;
			}
		}
		return new OAuthTokens(accessToken, (refreshToken != null) ? refreshToken : previousRefreshToken, expiresAt,
				ProviderHttpClient.text(json, "scope"));
	}

	@Override
	public String toString() {
		return "OAuthTokens[redacted, expiresAt=" + expiresAt + ", scope=" + scope + "]";
	}

}
