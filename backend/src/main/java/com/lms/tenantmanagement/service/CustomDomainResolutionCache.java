package com.lms.tenantmanagement.service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * TEN-DOM-01 / ADR-021 decision 3: Redis cache of custom hostname -> tenant id,
 * including "no such domain" (short-lived, so floods of unknown hosts don't
 * each hit the database). A cache only: every Redis failure falls back to the
 * database, and PostgreSQL stays the source of truth.
 *
 * <p>Status changes evict after their transaction commits - evicting before
 * commit would let a concurrent request re-cache the old state.
 */
@Component
public class CustomDomainResolutionCache {

	private static final Logger log = LoggerFactory.getLogger(CustomDomainResolutionCache.class);

	private static final String PREFIX = "tenant:custom-domain:";

	private static final String NONE = "-";

	private static final Duration FOUND_TTL = Duration.ofMinutes(5);

	private static final Duration NONE_TTL = Duration.ofSeconds(60);

	/** A cache hit; {@code tenantId} is null for a cached "no such domain". */
	public record Hit(UUID tenantId) {

	}

	private final StringRedisTemplate redis;

	public CustomDomainResolutionCache(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public Optional<Hit> get(String hostname) {
		try {
			String value = redis.opsForValue().get(PREFIX + hostname);
			if (value == null) {
				return Optional.empty();
			}
			return Optional.of(new Hit(NONE.equals(value) ? null : UUID.fromString(value)));
		}
		catch (RuntimeException ex) {
			warn("get", ex);
			return Optional.empty();
		}
	}

	public void put(String hostname, UUID tenantIdOrNull) {
		try {
			if (tenantIdOrNull == null) {
				redis.opsForValue().set(PREFIX + hostname, NONE, NONE_TTL);
			}
			else {
				redis.opsForValue().set(PREFIX + hostname, tenantIdOrNull.toString(), FOUND_TTL);
			}
		}
		catch (RuntimeException ex) {
			warn("put", ex);
		}
	}

	public void evictAfterCommit(String hostname) {
		evictAfterCommit(List.of(hostname));
	}

	public void evictAfterCommit(Collection<String> hostnames) {
		if (hostnames.isEmpty()) {
			return;
		}
		List<String> keys = hostnames.stream().map(host -> PREFIX + host).toList();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					evict(keys);
				}
			});
		}
		else {
			evict(keys);
		}
	}

	private void evict(List<String> keys) {
		try {
			redis.delete(keys);
		}
		catch (RuntimeException ex) {
			// Entries expire on their own within FOUND_TTL.
			warn("evict", ex);
		}
	}

	private static void warn(String operation, RuntimeException ex) {
		log.atWarn()
			.setMessage("custom_domain.cache_unavailable")
			.addKeyValue("operation", operation)
			.addKeyValue("errorType", ex.getClass().getSimpleName())
			.log();
	}

}
