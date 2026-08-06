package com.lms.identityaccessservice.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fail-fast guard for {@link JwtProperties#getSecret()}: {@code
 * application.yml} never binds {@code app.security.jwt.secret} (only {@code
 * application-local.yml} does, via the {@code JWT_SECRET} env var), so
 * outside the {@code local}/{@code test} profiles a missing {@code
 * JWT_SECRET} would otherwise leave {@link JwtProperties#getSecret()} as the
 * well-known, publicly-visible {@link JwtProperties#PLACEHOLDER_SECRET}
 * value - full JWT-forgery risk.
 *
 * <p>Runs on {@link ApplicationReadyEvent} (after the context has fully
 * refreshed) so it applies to a real Spring Boot startup without disturbing
 * the {@code local}/{@code test} profiles, where the placeholder default is
 * expected and relied upon by tests that construct {@link JwtProperties}
 * directly. Throwing here still prevents {@code SpringApplication.run(...)}
 * from returning normally, so the application does not finish starting.
 */
@Component
public class JwtSecretStartupValidator implements ApplicationListener<ApplicationReadyEvent> {

	private final Environment environment;

	private final JwtProperties jwtProperties;

	public JwtSecretStartupValidator(Environment environment, JwtProperties jwtProperties) {
		this.environment = environment;
		this.jwtProperties = jwtProperties;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
			return;
		}
		String secret = jwtProperties.getSecret();
		if (!StringUtils.hasText(secret) || JwtProperties.PLACEHOLDER_SECRET.equals(secret)) {
			throw new IllegalStateException(
					"app.security.jwt.secret must be set via the JWT_SECRET environment variable outside the "
							+ "local/test profiles - refusing to start with the placeholder development secret");
		}
	}

}
