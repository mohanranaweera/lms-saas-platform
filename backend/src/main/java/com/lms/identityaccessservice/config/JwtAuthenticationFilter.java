package com.lms.identityaccessservice.config;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextNotResolvedException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import com.lms.identityaccessservice.service.DeviceSessionCacheService;
import com.lms.identityaccessservice.service.ParsedToken;
import com.lms.identityaccessservice.service.TokenService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates a {@code Bearer} access token, cross-checks its {@code
 * tenant_id} claim against the already-resolved {@link TenantContext}
 * (rejecting on any mismatch - this is what makes the mandatory cross-tenant
 * replay test pass), checks session validity via the Redis-then-Postgres
 * path, and re-reads the CURRENT role/status from {@code
 * TenantUserRepository}/{@code PlatformAdminUserRepository} - the JWT
 * {@code role} claim is never trusted as final (AUTH-2). Populates {@link
 * SecurityContextHolder} and {@link AuthenticatedPrincipalHolder}, clearing
 * both in a {@code finally} block.
 *
 * <p>A request with no {@code Authorization} header is passed through
 * untouched - whether authentication is required for that path is decided
 * by {@code SecurityFilterChainConfig}'s authorization rules, not here.
 *
 * <p>Deliberately NOT {@code @Component} - see {@link TenantResolutionFilter}'s
 * javadoc for why (this filter's dependency graph - JPA repositories, Redis,
 * {@code TokenService} - is even less appropriate for an unrelated {@code
 * @WebMvcTest} slice to have to satisfy). Registered exclusively via the
 * {@code @Bean} factory method in {@link SecurityFilterChainConfig}.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final TokenService tokenService;

	private final TenantContext tenantContext;

	private final DeviceSessionRepository deviceSessionRepository;

	private final PlatformAdminSessionRepository platformAdminSessionRepository;

	private final TenantUserRepository tenantUserRepository;

	private final PlatformAdminUserRepository platformAdminUserRepository;

	private final DeviceSessionCacheService cacheService;

	private final ObjectMapper objectMapper;

	public JwtAuthenticationFilter(TokenService tokenService, TenantContext tenantContext,
			DeviceSessionRepository deviceSessionRepository,
			PlatformAdminSessionRepository platformAdminSessionRepository, TenantUserRepository tenantUserRepository,
			PlatformAdminUserRepository platformAdminUserRepository, DeviceSessionCacheService cacheService,
			ObjectMapper objectMapper) {
		this.tokenService = tokenService;
		this.tenantContext = tenantContext;
		this.deviceSessionRepository = deviceSessionRepository;
		this.platformAdminSessionRepository = platformAdminSessionRepository;
		this.tenantUserRepository = tenantUserRepository;
		this.platformAdminUserRepository = platformAdminUserRepository;
		this.cacheService = cacheService;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}
		String token = authorizationHeader.substring(7);

		ParsedToken parsedToken;
		try {
			parsedToken = tokenService.parseAndValidate(token);
		}
		catch (JwtException | IllegalArgumentException invalidToken) {
			unauthorized(response, ApiErrorCodes.UNAUTHENTICATED, "Invalid or expired access token");
			return;
		}

		if (parsedToken.isPlatformAdmin() && !request.getRequestURI().startsWith("/api/v1/platform-admin/")) {
			unauthorized(response, ApiErrorCodes.SESSION_REVOKED, "Your session is no longer valid. Please sign in again.");
			return;
		}

		AuthenticatedPrincipal principal = parsedToken.isPlatformAdmin() ? resolvePlatformAdminPrincipal(parsedToken)
				: resolveTenantPrincipal(parsedToken);

		if (principal == null) {
			unauthorized(response, ApiErrorCodes.SESSION_REVOKED, "Your session is no longer valid. Please sign in again.");
			return;
		}

		try {
			List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()));
			var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
			SecurityContextHolder.getContext().setAuthentication(authentication);
			AuthenticatedPrincipalHolder.set(principal);
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
			AuthenticatedPrincipalHolder.clear();
		}
	}

	private AuthenticatedPrincipal resolveTenantPrincipal(ParsedToken parsedToken) {
		UUID resolvedTenantId;
		try {
			resolvedTenantId = tenantContext.getTenantId();
		}
		catch (TenantContextNotResolvedException notResolved) {
			return null;
		}
		if (!resolvedTenantId.equals(parsedToken.tenantId())) {
			return null;
		}
		if (!isTenantSessionUsable(parsedToken.sessionId())) {
			return null;
		}
		Optional<TenantUser> userOpt = tenantUserRepository.findById(parsedToken.subject());
		if (userOpt.isEmpty() || userOpt.get().getStatus() != AccountStatus.ACTIVE) {
			return null;
		}
		TenantUser user = userOpt.get();
		return new AuthenticatedPrincipal(user.getId(), resolvedTenantId, user.getRole().name(), parsedToken.sessionId());
	}

	private AuthenticatedPrincipal resolvePlatformAdminPrincipal(ParsedToken parsedToken) {
		if (!isPlatformAdminSessionUsable(parsedToken.sessionId())) {
			return null;
		}
		Optional<PlatformAdminUser> adminOpt = platformAdminUserRepository.findById(parsedToken.subject());
		if (adminOpt.isEmpty() || adminOpt.get().getStatus() != AccountStatus.ACTIVE) {
			return null;
		}
		return new AuthenticatedPrincipal(adminOpt.get().getId(), null, TokenService.PLATFORM_ADMIN_ROLE,
				parsedToken.sessionId());
	}

	private boolean isTenantSessionUsable(UUID sessionId) {
		if (cacheService.isActive(sessionId)) {
			return true;
		}
		Optional<DeviceSession> sessionOpt = deviceSessionRepository.findById(sessionId);
		if (sessionOpt.isEmpty()) {
			return false;
		}
		DeviceSession session = sessionOpt.get();
		boolean usable = session.isUsable(Instant.now());
		if (usable) {
			cacheService.markActive(sessionId, session.getExpiresAt());
		}
		return usable;
	}

	private boolean isPlatformAdminSessionUsable(UUID sessionId) {
		if (cacheService.isActive(sessionId)) {
			return true;
		}
		var sessionOpt = platformAdminSessionRepository.findById(sessionId);
		if (sessionOpt.isEmpty()) {
			return false;
		}
		var session = sessionOpt.get();
		boolean usable = session.isUsable(Instant.now());
		if (usable) {
			cacheService.markActive(sessionId, session.getExpiresAt());
		}
		return usable;
	}

	private void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
		FilterApiResponseWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED, code, message);
	}

}
