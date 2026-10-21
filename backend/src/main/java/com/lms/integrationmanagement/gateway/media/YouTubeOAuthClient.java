package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Google OAuth for a teacher's YouTube channel (ADR-018). This phase asks
 * only for {@code youtube.readonly}; MEDIA-INT-02 adds upload via
 * incremental authorization ({@code include_granted_scopes}).
 * {@code access_type=offline} + {@code prompt=consent} guarantee a refresh
 * token. Google does not rotate the refresh token on refresh.
 */
@Component
public class YouTubeOAuthClient implements OAuthProviderClient {

	static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";

	/** VIDEO Phase 3: the tenant's own channel needs upload on top of read. */
	static final String UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload";

	/**
	 * VIDEO Phase 5 (ADR-018 Amendment 3): editing a video's title /
	 * description / privacy ({@code videos.update}) needs this scope. YouTube
	 * has no narrower edit-only scope; it also permits delete, which the
	 * platform never calls (Q-017).
	 */
	public static final String EDIT_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";

	private static final List<String> TENANT_CHANNEL_SCOPES = List.of(UPLOAD_SCOPE, SCOPE, EDIT_SCOPE);

	/** Whether a (possibly pre-Amendment-3) tenant channel grant can edit videos. */
	public static boolean grantsEditScope(String grantedScopes) {
		return grantedScopes != null
				&& Arrays.asList(grantedScopes.trim().split("\\s+")).contains(EDIT_SCOPE);
	}

	/**
	 * Consent URL for a tenant's own YouTube channel (VIDEO Phase 3, ADR-018
	 * Amendment 2): exactly upload + readonly, PKCE, offline access. No
	 * {@code include_granted_scopes} - the grant must not silently widen with
	 * whatever else this Google account approved for the platform before.
	 */
	public String tenantChannelAuthorizationUrl(UUID tenantId, String state, String codeChallenge,
			String redirectUri) {
		return UriComponentsBuilder.fromUriString(config.getAuthBaseUrl() + "/o/oauth2/v2/auth")
			.queryParam("response_type", "code")
			.queryParam("client_id", credentials.require(tenantId).clientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", String.join(" ", TENANT_CHANNEL_SCOPES))
			.queryParam("access_type", "offline")
			.queryParam("prompt", "consent")
			.queryParam("state", state)
			.queryParam("code_challenge", codeChallenge)
			.queryParam("code_challenge_method", "S256")
			.encode()
			.build()
			.toUriString();
	}

	/** Whether Google actually granted every scope a tenant channel needs (users can untick some). */
	public static boolean grantsTenantChannelScopes(String grantedScopes) {
		if (grantedScopes == null) {
			return false;
		}
		Set<String> granted = new HashSet<>(Arrays.asList(grantedScopes.trim().split("\\s+")));
		return granted.containsAll(TENANT_CHANNEL_SCOPES);
	}

	/** VIDEO Phase 5c: what Google said about a client id / secret pair. */
	public enum ClientCheck {

		/** Google accepted the client (it only refused our deliberately invalid code). */
		VALID,
		/** Google does not know this client id, or the secret is wrong. */
		REJECTED,
		/** Google answered something else - not conclusive. */
		INCONCLUSIVE

	}

	/** Endpoints (auth / token / API base URLs) only - the client id and secret come from {@link #credentials}. */
	private final MediaIntegrationProperties.OAuthProvider config;

	private final GoogleClientCredentials credentials;

	private final ProviderHttpClient http;

	public YouTubeOAuthClient(MediaIntegrationProperties properties, GoogleClientCredentials credentials,
			ProviderHttpClient http) {
		this.config = properties.getGoogle();
		this.credentials = credentials;
		this.http = http;
	}

	@Override
	public MediaProvider provider() {
		return MediaProvider.YOUTUBE;
	}

	@Override
	public boolean isConfigured() {
		return credentials.isConfigured();
	}

	/**
	 * VIDEO Phase 5c: asks Google's token endpoint whether this client id /
	 * secret pair exists, by redeeming a code that can't be valid. A known
	 * client gets {@code invalid_grant}; an unknown one or a wrong secret
	 * gets {@code invalid_client} (401). Nothing is granted or stored.
	 */
	public ClientCheck verifyClient(String clientId, String clientSecret, String redirectUri) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		form.add("grant_type", "authorization_code");
		form.add("code", "lms-client-check-not-a-real-code");
		form.add("redirect_uri", redirectUri);
		ProviderHttpClient.ProbeResult result = http.probeForm(URI.create(config.getTokenBaseUrl() + "/token"), form);
		if ("invalid_grant".equals(result.oauthError()) || "redirect_uri_mismatch".equals(result.oauthError())) {
			return ClientCheck.VALID;
		}
		if (result.status() == 401 || "invalid_client".equals(result.oauthError())
				|| "unauthorized_client".equals(result.oauthError())) {
			return ClientCheck.REJECTED;
		}
		return ClientCheck.INCONCLUSIVE;
	}

	@Override
	public boolean supportsPkce() {
		return true;
	}

	@Override
	public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
		return UriComponentsBuilder.fromUriString(config.getAuthBaseUrl() + "/o/oauth2/v2/auth")
			.queryParam("response_type", "code")
			.queryParam("client_id", credentials.require().clientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", SCOPE)
			.queryParam("access_type", "offline")
			.queryParam("prompt", "consent")
			.queryParam("include_granted_scopes", "true")
			.queryParam("state", state)
			.queryParam("code_challenge", codeChallenge)
			.queryParam("code_challenge_method", "S256")
			.encode()
			.build()
			.toUriString();
	}

	/**
	 * VIDEO Phase 5c: this client bound to one institute's Google app (its own,
	 * else the platform app) - used for that institute's channel connect,
	 * token exchange and refresh. {@code tenantId} must come from a trusted
	 * source (session, connection row or server-side OAuth state).
	 */
	public OAuthProviderClient forTenant(UUID tenantId) {
		YouTubeOAuthClient self = this;
		return new OAuthProviderClient() {

			@Override
			public MediaProvider provider() {
				return MediaProvider.YOUTUBE;
			}

			@Override
			public boolean isConfigured() {
				return credentials.isConfigured(tenantId);
			}

			@Override
			public boolean supportsPkce() {
				return true;
			}

			@Override
			public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
				return self.tenantChannelAuthorizationUrl(tenantId, state, codeChallenge, redirectUri);
			}

			@Override
			public OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
				return self.exchangeCode(credentials.require(tenantId), code, codeVerifier, redirectUri);
			}

			@Override
			public OAuthTokens refresh(String refreshToken) {
				return self.refresh(credentials.require(tenantId), refreshToken);
			}

			@Override
			public ProviderIdentity fetchIdentity(String accessToken) {
				return self.fetchIdentity(accessToken);
			}

			@Override
			public void revoke(OAuthTokens tokens) {
				self.revoke(tokens);
			}

		};
	}

	@Override
	public OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
		return exchangeCode(credentials.require(), code, codeVerifier, redirectUri);
	}

	private OAuthTokens exchangeCode(GoogleClientCredentials.Credentials client, String code, String codeVerifier,
			String redirectUri) {
		MultiValueMap<String, String> form = clientForm(client);
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", redirectUri);
		if (codeVerifier != null) {
			form.add("code_verifier", codeVerifier);
		}
		JsonNode json = http.postForm(URI.create(config.getTokenBaseUrl() + "/token"), form, headers -> {
		});
		return OAuthTokens.fromTokenResponse(json, null, Instant.now());
	}

	@Override
	public OAuthTokens refresh(String refreshToken) {
		return refresh(credentials.require(), refreshToken);
	}

	private OAuthTokens refresh(GoogleClientCredentials.Credentials client, String refreshToken) {
		MultiValueMap<String, String> form = clientForm(client);
		form.add("grant_type", "refresh_token");
		form.add("refresh_token", refreshToken);
		JsonNode json = http.postForm(URI.create(config.getTokenBaseUrl() + "/token"), form, headers -> {
		});
		return OAuthTokens.fromTokenResponse(json, refreshToken, Instant.now());
	}

	@Override
	public ProviderIdentity fetchIdentity(String accessToken) {
		URI uri = URI.create(config.getApiBaseUrl() + "/youtube/v3/channels?part=snippet&mine=true");
		JsonNode channels = http.get(uri, ProviderHttpClient.bearer(accessToken));
		JsonNode first = channels.path("items").path(0);
		String channelId = ProviderHttpClient.text(first, "id");
		if (channelId == null) {
			throw new MediaProviderException(MediaProviderException.Kind.REJECTED,
					"This Google account has no YouTube channel.");
		}
		return new ProviderIdentity(channelId, ProviderHttpClient.text(first.path("snippet"), "title"), null);
	}

	@Override
	public void revoke(OAuthTokens tokens) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("token", (tokens.refreshToken() != null) ? tokens.refreshToken() : tokens.accessToken());
		http.postForm(URI.create(config.getTokenBaseUrl() + "/revoke"), form, headers -> {
		});
	}

	private static MultiValueMap<String, String> clientForm(GoogleClientCredentials.Credentials client) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", client.clientId());
		form.add("client_secret", client.clientSecret());
		return form;
	}

}
