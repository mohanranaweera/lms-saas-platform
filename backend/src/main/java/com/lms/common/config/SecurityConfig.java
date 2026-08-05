package com.lms.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security posture until identity-access-service (ADR-007) exists
 * to supply real authentication. Only the operational endpoints needed for
 * the platform to run at all (health checks, local API documentation) are
 * permitted without authentication; everything else is denied outright
 * rather than left open or gated behind Spring Boot's auto-generated Basic
 * Auth password, since there is no real authentication/authorization model
 * yet to decide otherwise.
 *
 * Stateless (no session) per .claude/rules/architecture.md's "all
 * application instances must be stateless" requirement; CSRF protection is
 * disabled accordingly, consistent with a token-based API carrying no
 * browser session cookie.
 *
 * Replace/extend this once identity-access-service ships real
 * authentication - this is a deliberate placeholder, not a permanent
 * security design.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, Environment environment) throws Exception {
		boolean exposeApiDocs = environment.acceptsProfiles(Profiles.of("local", "test"));
		http.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> {
				authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
				if (exposeApiDocs) {
					authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
				}
				authorize.anyRequest().denyAll();
			});
		return http.build();
	}

}
