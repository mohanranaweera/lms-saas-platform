package com.lms.identityaccessservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refresh-token/session lifetime configuration. {@code refreshTokenTtl} is
 * the absolute lifetime cap ({@code device_session.expires_at}, fixed at
 * issuance and unaffected by rotation) - ADR-007 only "recommends" 30 days,
 * it is not a ratified constant, so this is config-driven rather than a
 * hardcoded literal (plan §21). {@code cacheTtl} bounds how long {@code
 * DeviceSessionCacheService}'s Redis fast-path may report a session as valid
 * before the next Postgres read - short by design, defense in depth against
 * the logout-invalidation race (plan §21), never the sole source of truth.
 */
@Component
@ConfigurationProperties(prefix = "app.security.session")
public class SessionProperties {

	private Duration refreshTokenTtl = Duration.ofDays(30);

	private Duration cacheTtl = Duration.ofSeconds(60);

	public Duration getRefreshTokenTtl() {
		return refreshTokenTtl;
	}

	public void setRefreshTokenTtl(Duration refreshTokenTtl) {
		this.refreshTokenTtl = refreshTokenTtl;
	}

	public Duration getCacheTtl() {
		return cacheTtl;
	}

	public void setCacheTtl(Duration cacheTtl) {
		this.cacheTtl = cacheTtl;
	}

}
