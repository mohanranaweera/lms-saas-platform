package com.lms.identityaccessservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT signing configuration (ADR-007: HS256, single self-issued secret).
 * {@code secret} MUST be overridden via the {@code JWT_SECRET} env var in
 * every real environment - the field default here is a clearly-fake,
 * dev-only placeholder (see {@code application-local.yml}/{@code
 * .env.example}), long enough to satisfy HS256's minimum 256-bit key length
 * so local startup never silently falls back to a weak key.
 */
@Component
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtProperties {

	/**
	 * The clearly-fake, dev-only placeholder value {@link #secret} defaults to.
	 * Exposed so startup-time checks (e.g. {@link JwtSecretStartupValidator}) can
	 * detect an unset/unconfigured secret outside {@code local}/{@code test}
	 * without duplicating this literal.
	 */
	public static final String PLACEHOLDER_SECRET = "dev-only-placeholder-secret-change-me-before-any-real-deployment-32bytes+";

	private String secret = PLACEHOLDER_SECRET;

	private Duration accessTokenTtl = Duration.ofMinutes(15);

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public Duration getAccessTokenTtl() {
		return accessTokenTtl;
	}

	public void setAccessTokenTtl(Duration accessTokenTtl) {
		this.accessTokenTtl = accessTokenTtl;
	}

}
