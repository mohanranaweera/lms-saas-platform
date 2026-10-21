package com.lms.integrationmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.gateway.media.OAuthProviderClient;
import com.lms.integrationmanagement.gateway.media.OAuthTokens;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.CredentialContext;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.StoredCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient;
import com.lms.integrationmanagement.domain.ConnectionScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Hands out a usable access token for a teacher's OAuth connection
 * (ADR-018 §7), refreshing it server-side when it is within 5 minutes of
 * expiry.
 *
 * <p>Zoom rotates refresh tokens, so two concurrent refreshes would
 * invalidate each other: a per-connection Redis lock ({@code SET NX EX 30})
 * lets only one request refresh. A request that finds the lock taken never waits: it uses the
 * still-valid current token, or fails fast if that has expired. The HTTP call
 * runs with no DB transaction open; the new tokens are
 * then persisted in a short transaction ({@link MediaConnectionStore}).
 *
 * <p>Deliberately NOT {@code @Transactional}.
 */
@Service
public class MediaTokenService {

	private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

	private static final Duration LOCK_TTL = Duration.ofSeconds(30);

	private static final String LOCK_PREFIX = "media:refresh-lock:";

	private final MediaConnectionStore store;

	private final MediaCredentialsCodec codec;

	private final StringRedisTemplate redisTemplate;

	private final Map<MediaProvider, OAuthProviderClient> clients = new EnumMap<>(MediaProvider.class);

	public MediaTokenService(MediaConnectionStore store, MediaCredentialsCodec codec,
			StringRedisTemplate redisTemplate, List<OAuthProviderClient> oauthClients) {
		this.store = store;
		this.codec = codec;
		this.redisTemplate = redisTemplate;
		oauthClients.forEach(client -> clients.put(client.provider(), client));
	}

	/**
	 * @throws ProviderConflictException {@code PROVIDER_RECONNECT_REQUIRED} when
	 * the connection is not ACTIVE or the provider rejects the refresh
	 * @throws MediaProviderException UNAVAILABLE when the provider cannot be
	 * reached, or another request is mid-refresh and the current token has
	 * already expired
	 */
	public String validAccessToken(UUID connectionId) {
		return accessToken(connectionId, false);
	}

	/**
	 * VIDEO Phase 4: refreshes even though the stored token has not expired
	 * yet - for a provider that just answered 401 to that token. Same lock and
	 * failure semantics as {@link #validAccessToken}; if another caller is
	 * refreshing right now this fails with UNAVAILABLE (retry later).
	 */
	public String refreshedAccessToken(UUID connectionId) {
		return accessToken(connectionId, true);
	}

	private String accessToken(UUID connectionId, boolean force) {
		MediaProviderConnection connection = store.require(connectionId);
		requireActive(connection);
		if (!force && !needsRefresh(connection)) {
			return codec.open(connection).accessToken();
		}
		String lockKey = LOCK_PREFIX + connectionId;
		if (!tryLock(lockKey)) {
			// Another request is refreshing right now. Never block a request
			// thread waiting for it: inside the refresh margin the current
			// token is usually still valid, so use it; if it has actually
			// expired, ask the caller to retry in a moment.
			if (!force && isStillValid(connection)) {
				return codec.open(connection).accessToken();
			}
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"The provider connection is busy. Try again in a moment.");
		}
		try {
			// Re-read under the lock: another request may have just refreshed.
			connection = store.require(connectionId);
			requireActive(connection);
			if (!force && !needsRefresh(connection)) {
				return codec.open(connection).accessToken();
			}
			CredentialContext context = CredentialContext.of(connection);
			StoredCredentials current = codec.open(connection.getCredentialsCiphertext(), context);
			OAuthTokens refreshed;
			try {
				refreshed = client(connection).refresh(current.refreshToken());
			}
			catch (MediaProviderException ex) {
				if (ex.getKind() == MediaProviderException.Kind.AUTH_REJECTED) {
					store.markNeedsReconnect(connectionId, "The provider no longer accepts this connection.");
					throw reconnectRequired(connection.getProvider());
				}
				throw ex;
			}
			store.storeRefreshedTokens(connectionId,
					codec.seal(StoredCredentials.oauth(refreshed.accessToken(), refreshed.refreshToken()), context),
					refreshed.expiresAt());
			return refreshed.accessToken();
		}
		finally {
			releaseLock(lockKey);
		}
	}

	/** Current tokens without refreshing - used for best-effort revocation. */
	public OAuthTokens currentTokens(MediaProviderConnection connection) {
		StoredCredentials stored = codec.open(connection);
		return new OAuthTokens(stored.accessToken(), stored.refreshToken(), connection.getAccessTokenExpiresAt(),
				connection.getGrantedScopes());
	}

	/** An API call with a valid-looking token was rejected: the grant was revoked at the provider. */
	public ProviderConflictException markRejected(UUID connectionId, MediaProvider provider) {
		store.markNeedsReconnect(connectionId, "The provider no longer accepts this connection.");
		return reconnectRequired(provider);
	}

	/**
	 * VIDEO Phase 5c: an institute's YouTube channel refreshes with that
	 * institute's Google app (its own, else the platform app); the tenant is
	 * the connection row's own.
	 */
	OAuthProviderClient client(MediaProviderConnection connection) {
		OAuthProviderClient client = client(connection.getProvider());
		if (client instanceof YouTubeOAuthClient youTube && connection.getScope() == ConnectionScope.TENANT) {
			return youTube.forTenant(connection.getTenantId());
		}
		return client;
	}

	OAuthProviderClient client(MediaProvider provider) {
		OAuthProviderClient client = clients.get(provider);
		if (client == null) {
			throw new IllegalArgumentException(provider + " has no OAuth client");
		}
		return client;
	}

	private static boolean isStillValid(MediaProviderConnection connection) {
		Instant expiresAt = connection.getAccessTokenExpiresAt();
		return expiresAt == null || Instant.now().isBefore(expiresAt);
	}

	private static boolean needsRefresh(MediaProviderConnection connection) {
		Instant expiresAt = connection.getAccessTokenExpiresAt();
		return expiresAt != null && Instant.now().plus(REFRESH_MARGIN).isAfter(expiresAt);
	}

	private static void requireActive(MediaProviderConnection connection) {
		if (!connection.isActive()) {
			throw reconnectRequired(connection.getProvider());
		}
	}

	private static ProviderConflictException reconnectRequired(MediaProvider provider) {
		return new ProviderConflictException(ApiErrorCodes.PROVIDER_RECONNECT_REQUIRED,
				"The " + displayName(provider) + " account needs to be reconnected.");
	}

	static String displayName(MediaProvider provider) {
		return switch (provider) {
			case ZOOM -> "Zoom";
			case YOUTUBE -> "YouTube";
			case VIMEO -> "Vimeo";
			case DYNTUBE -> "DynTube";
			case BUNNY -> "Bunny.net";
		};
	}

	private boolean tryLock(String key) {
		try {
			return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL));
		}
		catch (DataAccessException ex) {
			throw new ServiceUnavailableException("Integrations are not available right now.");
		}
	}

	private void releaseLock(String key) {
		try {
			redisTemplate.delete(key);
		}
		catch (DataAccessException ex) {
			// The lock expires on its own (LOCK_TTL).
		}
	}

}
