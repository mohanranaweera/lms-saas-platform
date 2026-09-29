package com.lms.integrationmanagement.service;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaProvider;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Single-use OAuth {@code state} values (ADR-018 §3), held in Redis only.
 * The state is the ONLY thing the public callback trusts to decide which
 * tenant and teacher a returning authorization belongs to: 256 random bits,
 * a 10-minute TTL, and consumed atomically ({@code GETDEL}) so it can never
 * be replayed.
 *
 * <p>It also holds the second half of the flow (security review S1): a
 * finished authorization waits here, keyed by a fresh single-use pending id,
 * until the SAME signed-in teacher confirms it. The pending record carries the
 * provider credentials only as ciphertext.
 */
@Component
public class OAuthStateStore {

	private static final String KEY_PREFIX = "oauth:state:";

	private static final String PENDING_PREFIX = "oauth:pending:";

	private static final Duration TTL = Duration.ofMinutes(10);

	private static final SecureRandom RANDOM = new SecureRandom();

	/** What a state value stands for. {@code codeVerifier} is the PKCE verifier, or {@code null}. */
	public record PendingAuthorization(UUID tenantId, UUID userId, MediaProvider provider, String codeVerifier) {

		@Override
		public String toString() {
			return "PendingAuthorization[tenantId=" + tenantId + ", userId=" + userId + ", provider=" + provider + "]";
		}

	}

	/**
	 * A provider authorization that has completed but is not yet saved. {@code
	 * credentialsCiphertext} is sealed for this tenant/provider/teacher.
	 */
	public record PendingConnection(UUID tenantId, UUID userId, MediaProvider provider, String externalAccountId,
			String accountLabel, String accountType, String credentialsCiphertext, Instant accessTokenExpiresAt,
			String grantedScopes) {

		@Override
		public String toString() {
			return "PendingConnection[tenantId=" + tenantId + ", userId=" + userId + ", provider=" + provider + "]";
		}

	}

	private final StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper;

	public OAuthStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/** @return the opaque state value to send to the provider */
	public String issue(PendingAuthorization pending) {
		String state = randomToken();
		try {
			redisTemplate.opsForValue().set(KEY_PREFIX + state, objectMapper.writeValueAsString(pending), TTL);
		}
		catch (DataAccessException ex) {
			throw new ServiceUnavailableException("Integrations are not available right now.");
		}
		return state;
	}

	/** Atomically reads and deletes; empty for an unknown, expired or already-used state. */
	public Optional<PendingAuthorization> consume(String state) {
		if (state == null || state.isBlank() || state.length() > 128) {
			return Optional.empty();
		}
		String json;
		try {
			json = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
		}
		catch (DataAccessException ex) {
			return Optional.empty();
		}
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(json, PendingAuthorization.class));
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
	}

	/** @return the single-use pending id to hand back to the browser */
	public String storePending(PendingConnection pending) {
		String pendingId = randomToken();
		try {
			redisTemplate.opsForValue()
				.set(PENDING_PREFIX + pendingId, objectMapper.writeValueAsString(pending), TTL);
		}
		catch (DataAccessException ex) {
			throw new ServiceUnavailableException("Integrations are not available right now.");
		}
		return pendingId;
	}

	/** Atomically reads and deletes; empty for an unknown, expired or already-used id. */
	public Optional<PendingConnection> consumePending(String pendingId) {
		if (pendingId == null || pendingId.isBlank() || pendingId.length() > 128) {
			return Optional.empty();
		}
		try {
			String json = redisTemplate.opsForValue().getAndDelete(PENDING_PREFIX + pendingId);
			return (json == null) ? Optional.empty()
					: Optional.of(objectMapper.readValue(json, PendingConnection.class));
		}
		catch (DataAccessException | JacksonException ex) {
			return Optional.empty();
		}
	}

	/** 256 random bits, URL-safe Base64 without padding - used for both state and PKCE verifiers. */
	static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
