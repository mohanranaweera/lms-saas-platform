package com.lms.identityaccessservice.config;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.config.CorrelationIdFilter;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import com.lms.identityaccessservice.service.DeviceSessionCacheService;
import com.lms.identityaccessservice.service.TokenService;
import com.lms.tenantmanagement.api.TenantLookupApi;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Real authentication chain (replaces the {@code com.lms.common.config.SecurityConfig}
 * placeholder from Application Foundation - deliberately not defined in
 * {@code com.lms.common}, since the shared kernel must never depend back on
 * a business/domain module such as identity-access-service, per
 * .claude/rules/architecture.md).
 *
 * <p>Filter order (plan §9, with the reorder called for by its own
 * "CorrelationIdFilter ordering note"): {@code TenantResolutionFilter} runs
 * FIRST so tenant identity is resolved before {@code CorrelationIdFilter}
 * puts it into MDC (otherwise that line is dead code, since it never sees a
 * resolved tenant) and before any credential check; {@code
 * JwtAuthenticationFilter} runs last, once both tenant context and
 * correlation id are in place.
 *
 * <p>{@code TenantResolutionFilter}/{@code JwtAuthenticationFilter} are
 * constructed here via explicit {@code @Bean} factory methods, not {@code
 * @Component} (see their own javadoc for why - it keeps unrelated {@code
 * @WebMvcTest} slices from trying to auto-detect and construct them with a
 * dependency graph those slices were never meant to satisfy). {@code
 * CorrelationIdFilter} remains the pre-existing {@code @Component} from
 * Application Foundation. All three have their automatic generic-servlet
 * -filter registration explicitly disabled below - they are added to Spring
 * Security's own chain instead via {@code addFilterBefore}/{@code
 * addFilterAfter}, so each runs exactly once, in the exact order declared
 * here.
 */
@Configuration
public class SecurityFilterChainConfig {

	@Bean
	public TenantResolutionFilter tenantResolutionFilter(TenantLookupApi tenantLookupApi, ObjectMapper objectMapper) {
		return new TenantResolutionFilter(tenantLookupApi, objectMapper);
	}

	@Bean
	public JwtAuthenticationFilter jwtAuthenticationFilter(TokenService tokenService, TenantContext tenantContext,
			DeviceSessionRepository deviceSessionRepository,
			PlatformAdminSessionRepository platformAdminSessionRepository, TenantUserRepository tenantUserRepository,
			PlatformAdminUserRepository platformAdminUserRepository, DeviceSessionCacheService cacheService,
			ObjectMapper objectMapper) {
		return new JwtAuthenticationFilter(tokenService, tenantContext, deviceSessionRepository,
				platformAdminSessionRepository, tenantUserRepository, platformAdminUserRepository, cacheService,
				objectMapper);
	}

	@Bean
	public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
		FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	public FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilterRegistration(
			TenantResolutionFilter filter) {
		FilterRegistrationBean<TenantResolutionFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
			JwtAuthenticationFilter filter) {
		FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	public AuthenticationEntryPoint apiAuthenticationEntryPoint(ObjectMapper objectMapper) {
		return (request, response, authException) -> FilterApiResponseWriter.write(response, objectMapper,
				HttpStatus.UNAUTHORIZED, ApiErrorCodes.UNAUTHENTICATED, "Authentication is required");
	}

	@Bean
	public AccessDeniedHandler apiAccessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, accessDeniedException) -> FilterApiResponseWriter.write(response, objectMapper,
				HttpStatus.FORBIDDEN, ApiErrorCodes.FORBIDDEN, "You do not have permission to perform this action");
	}

	/**
	 * CORS is intentionally undefined for every profile except {@code local}.
	 * Non-local environments are expected to serve frontend and backend
	 * same-origin (behind Nginx per {@code docs/architecture/deployment-architecture.md}),
	 * so no cross-origin browser requests should ever need to be allowed there.
	 * This bean exists solely so a browser-based frontend dev server on a
	 * different port ({@code http://localhost:3000}) can make credentialed
	 * requests to this app during local development - closing the "CORS
	 * caveat" flagged in {@code docs/api/identity-access-service.md}. Allowed
	 * origins are read from {@code app.security.cors.allowed-origins} (set
	 * only in {@code application-local.yml}) rather than hardcoded, and the
	 * bean is additionally gated to {@code @Profile("local")} as a second,
	 * structural guard - even a misconfigured property can never enable CORS
	 * outside local development, since {@link
	 * org.springframework.security.config.annotation.web.configurers.CorsConfigurer}
	 * is a no-op when no {@link CorsConfigurationSource} bean exists.
	 */
	@Bean
	@Profile("local")
	public CorsConfigurationSource corsConfigurationSource(
			@Value("${app.security.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, Environment environment,
			CorrelationIdFilter correlationIdFilter, TenantResolutionFilter tenantResolutionFilter,
			JwtAuthenticationFilter jwtAuthenticationFilter, AuthenticationEntryPoint authenticationEntryPoint,
			AccessDeniedHandler accessDeniedHandler) throws Exception {
		boolean exposeApiDocs = environment.acceptsProfiles(Profiles.of("local", "test"));
		http.csrf(csrf -> csrf.disable())
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(handling -> handling.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.authorizeHttpRequests(authorize -> {
				authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
				if (exposeApiDocs) {
					authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
				}
				// The one legitimate public/anonymous business endpoint - tenant
				// self-registration (TEN-1); see docs/plans/MVP-004 Tenant
				// Management.md §15. Also excluded from TenantResolutionFilter
				// itself (shouldNotFilter) since a prospective institute has no
				// subdomain to resolve yet.
				authorize.requestMatchers(HttpMethod.POST, "/api/v1/tenant-registrations").permitAll();
				authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll();
				authorize
					.requestMatchers(HttpMethod.POST, "/api/v1/platform-admin/auth/login",
							"/api/v1/platform-admin/auth/refresh")
					.permitAll();
				authorize.anyRequest().authenticated();
			})
			.addFilterBefore(tenantResolutionFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(correlationIdFilter, TenantResolutionFilter.class)
			.addFilterAfter(jwtAuthenticationFilter, CorrelationIdFilter.class);
		return http.build();
	}

}
