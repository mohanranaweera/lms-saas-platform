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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import java.util.List;

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

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, Environment environment,
			CorrelationIdFilter correlationIdFilter, TenantResolutionFilter tenantResolutionFilter,
			JwtAuthenticationFilter jwtAuthenticationFilter, AuthenticationEntryPoint authenticationEntryPoint,
			AccessDeniedHandler accessDeniedHandler) throws Exception {
		boolean exposeApiDocs = environment.acceptsProfiles(Profiles.of("local", "test"));
		boolean enableLocalCors = environment.acceptsProfiles(Profiles.of("local"));
		if (enableLocalCors) {
			http.cors(cors -> cors.configurationSource(localCorsConfigurationSource()));
		}
		http.csrf(csrf -> csrf.disable())
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
				// Public, unauthenticated course-management storefront read path
				// (MVP-008, GET-only) - tenant is still resolved server-side from
				// the subdomain by TenantResolutionFilter; no client-supplied
				// tenantId is ever accepted. See CoursePublicController.
				authorize.requestMatchers(HttpMethod.GET, "/api/v1/public/courses", "/api/v1/public/courses/**")
					.permitAll();
				// Public, unauthenticated branding read path (Wave 1 - Tenant
				// Admin IA + Configuration Framework) - tenant is still
				// resolved server-side from the subdomain by
				// TenantResolutionFilter; no client-supplied tenantId is ever
				// accepted. See PublicBrandingController for why this one
				// endpoint deliberately bypasses BRANDING_SETTINGS's
				// permission gate.
				authorize.requestMatchers(HttpMethod.GET, "/api/v1/public/tenant-config/branding").permitAll();
				authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll();
				authorize
					.requestMatchers(HttpMethod.POST, "/api/v1/platform-admin/auth/login",
							"/api/v1/platform-admin/auth/refresh")
					.permitAll();
				// Payment-gateway server-to-server webhook (MVP-010/PAY-2) -
				// no session/JWT exists for this call (see
				// TenantResolutionFilter's matching exclusion above and
				// paymentmanagement.payment.service.PaymentConfirmationService's
				// javadoc). Authenticity is enforced instead by
				// PaymentWebhookController verifying the gateway's HMAC
				// signature over the raw request body before any state
				// change - signature-verified, not session-authenticated,
				// per .claude/rules/security.md and
				// .claude/rules/payments.md's webhook-verification rules.
				authorize.requestMatchers(HttpMethod.POST, "/api/v1/integrations/webhooks/**").permitAll();
				authorize.anyRequest().authenticated();
			})
			.addFilterBefore(tenantResolutionFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(correlationIdFilter, TenantResolutionFilter.class)
			.addFilterAfter(jwtAuthenticationFilter, CorrelationIdFilter.class);
		return http.build();
	}

	/**
	 * Local-dev-only CORS policy so the Next.js dev server (a different origin
	 * from this API) can call it during `local` profile development. Never
	 * enabled outside {@code local} - not wired into {@code filterChain} unless
	 * {@code enableLocalCors} is true, so test/staging/production origins get
	 * no CORS headers at all (browsers default-deny cross-origin, matching
	 * current behavior there). Credentials are allowed (refresh-token cookie),
	 * so origins must be named explicitly - {@code allowedOrigins("*")} is not
	 * legal together with {@code allowCredentials(true)}.
	 *
	 * <p>{@code http://demo.lms.test:3000} is included alongside {@code
	 * http://localhost:3000} because the refresh-token cookie is {@code
	 * SameSite=Strict} (see {@code RefreshCookieSupport}): a browser only sends
	 * a {@code SameSite=Strict} cookie back on a same-site request, and {@code
	 * localhost} and {@code demo.lms.test} are different sites even though
	 * both resolve to 127.0.0.1 locally. Browsing the frontend at {@code
	 * demo.lms.test:3000} (same registrable domain, {@code lms.test}, as the
	 * API's {@code demo.lms.test:8080}) is what actually lets refresh/session
	 * persistence work locally - {@code localhost:3000} still works for
	 * Platform Admin (no tenant subdomain involved) but not for tenant-scoped
	 * session refresh.
	 */
	private CorsConfigurationSource localCorsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://demo.lms.test:3000"));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Content-Type", "Authorization"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
