package com.lms.identityaccessservice.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fail-fast guard for {@code app.security.jwt.secret} (bound onto {@link
 * JwtProperties#getSecret()}): {@code application.yml} never binds it (only
 * {@code application-local.yml} does, via the {@code JWT_SECRET} env var), so
 * outside the {@code local}/{@code test} profiles a missing {@code
 * JWT_SECRET} would otherwise leave it as the well-known, publicly-visible
 * {@link JwtProperties#PLACEHOLDER_SECRET} value - full JWT-forgery risk.
 *
 * <p>Registered as an {@link ApplicationContextInitializer} from {@code
 * LmsBackendApplication#main} - deliberately NOT a {@code @Component}/{@code
 * ApplicationListener<ApplicationReadyEvent>} (this class's original form).
 * {@code ApplicationReadyEvent} fires only after {@code
 * ConfigurableApplicationContext#refresh()} has already called {@code
 * onRefresh()}, which is what starts the embedded web server accepting
 * connections - checking that late leaves a real window where the port is
 * open and {@code JwtAuthenticationFilter} is already wired to accept tokens
 * signed with the public placeholder secret, before this check has a chance
 * to throw and stop the process. An {@code ApplicationContextInitializer}
 * runs during {@code SpringApplication#run}'s {@code prepareContext} step,
 * strictly before {@code refresh()} - so a throw here means the port never
 * opens at all with an invalid secret.
 *
 * <p>No bean (including {@link JwtProperties} itself) exists yet at this
 * lifecycle stage, so this reads the raw environment property directly
 * rather than injecting {@link JwtProperties} - Spring's {@code
 * Environment#getProperty} already resolves the {@code ${JWT_SECRET}}
 * placeholder {@code application-local.yml} binds it through.
 */
public class JwtSecretStartupValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	static final String SECRET_PROPERTY = "app.security.jwt.secret";

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		ConfigurableEnvironment environment = applicationContext.getEnvironment();
		if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
			return;
		}
		String secret = environment.getProperty(SECRET_PROPERTY);
		if (!StringUtils.hasText(secret) || JwtProperties.PLACEHOLDER_SECRET.equals(secret)) {
			throw new IllegalStateException(
					"app.security.jwt.secret must be set via the JWT_SECRET environment variable outside the "
							+ "local/test profiles - refusing to start with the placeholder development secret");
		}
	}

}
