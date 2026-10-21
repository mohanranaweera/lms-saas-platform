package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.api.MediaProvider;

/**
 * One teacher-level provider's OAuth 2.0 authorization-code flow
 * (MEDIA-INT-01 / ADR-018 §3). Implementations are stateless; every network
 * method throws {@link MediaProviderException} on failure and must be called
 * outside any DB transaction.
 */
public interface OAuthProviderClient {

	MediaProvider provider();

	/** Whether the platform OAuth app (client id/secret) is configured. */
	boolean isConfigured();

	/** Whether this provider accepts PKCE ({@code code_challenge}, S256). */
	boolean supportsPkce();

	/**
	 * The consent URL the teacher's browser is sent to.
	 * @param codeChallenge S256 challenge, or {@code null} when {@link #supportsPkce()} is false
	 */
	String authorizationUrl(String state, String codeChallenge, String redirectUri);

	/** @param codeVerifier the PKCE verifier, or {@code null} */
	OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri);

	/** Exchanges a refresh token for fresh tokens. Only called when a token has an expiry. */
	OAuthTokens refresh(String refreshToken);

	ProviderIdentity fetchIdentity(String accessToken);

	/** Best-effort revocation at the provider. */
	void revoke(OAuthTokens tokens);

}
