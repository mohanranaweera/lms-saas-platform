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
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.service.DeviceSessionCacheService;
import com.lms.identityaccessservice.service.ImpersonationService;
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
import java.util.Set;
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

	private final ImpersonationService impersonationService;

	public JwtAuthenticationFilter(TokenService tokenService, TenantContext tenantContext,
			DeviceSessionRepository deviceSessionRepository,
			PlatformAdminSessionRepository platformAdminSessionRepository, TenantUserRepository tenantUserRepository,
			PlatformAdminUserRepository platformAdminUserRepository, DeviceSessionCacheService cacheService,
			ObjectMapper objectMapper, ImpersonationService impersonationService) {
		this.impersonationService = impersonationService;
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
				: parsedToken.impersonation() ? resolveImpersonationPrincipal(parsedToken)
						: resolveTenantPrincipal(parsedToken, request);

		if (principal == null) {
			unauthorized(response, ApiErrorCodes.SESSION_REVOKED, "Your session is no longer valid. Please sign in again.");
			return;
		}

		// ADR-033: a support session is read-only. Every state-changing request (so also
		// password changes, payments, enrollment, exam submits, attendance, watch time,
		// playback tokens, logout and refresh) is refused, except ending the session.
		if (principal.isImpersonation() && (!isReadOnlyMethod(request) || recordsStudentActivity(request))
				&& !IMPERSONATION_END_PATH.equals(request.getRequestURI())) {
			FilterApiResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN,
					ApiErrorCodes.IMPERSONATION_READ_ONLY, "A support session is read-only.");
			return;
		}

		// AUTH-PWD-01 / ADR-020: an account that must change its password may do
		// nothing else. Read from the account row loaded above (not a token
		// claim), so an admin-forced reset applies to the very next request.
		if (!parsedToken.isPlatformAdmin() && Boolean.TRUE.equals(request.getAttribute(MUST_CHANGE_PASSWORD_ATTRIBUTE))
				&& !allowedDuringPasswordChange(request)) {
			FilterApiResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN,
					ApiErrorCodes.PASSWORD_CHANGE_REQUIRED, "You must change your password before continuing.");
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

	static final String IMPERSONATION_END_PATH = "/api/v1/auth/impersonation/end";

	/**
	 * GET endpoints that nevertheless record student activity (a lesson material's signed
	 * download URL counts against the student's download limit) - refused in a support
	 * session too, so support browsing is never recorded as the student's activity.
	 */
	private static boolean recordsStudentActivity(HttpServletRequest request) {
		// Decoded + normalised (";params" stripped) - the raw URI would let
		// ".../download%2Durl" reach the handler while skipping this check.
		String path = DECODING_PATH_HELPER.getPathWithinApplication(request);
		return path.endsWith("/download-url") || path.endsWith("/download-url/");
	}

	private static final org.springframework.web.util.UrlPathHelper DECODING_PATH_HELPER = new org.springframework.web.util.UrlPathHelper();

	private static boolean isReadOnlyMethod(HttpServletRequest request) {
		String method = request.getMethod();
		return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
	}

	/**
	 * ADR-033: an impersonation token is valid only while its support session is open
	 * and unexpired, the admin account is ACTIVE and the admin's own device session is
	 * usable (checked by {@link ImpersonationService}); the subject must still be an
	 * ACTIVE student of this tenant. No device session is involved for the student, so
	 * a support session has no last-login or device-slot side effects.
	 */
	private AuthenticatedPrincipal resolveImpersonationPrincipal(ParsedToken parsedToken) {
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
		if (!impersonationService.isTokenSessionUsable(parsedToken.sessionId(), parsedToken.subject(),
				parsedToken.actorUserId())) {
			return null;
		}
		Optional<TenantUser> subject = tenantUserRepository.findById(parsedToken.subject());
		if (subject.isEmpty() || subject.get().getStatus() != AccountStatus.ACTIVE
				|| subject.get().getRole() != Role.STUDENT) {
			return null;
		}
		return new AuthenticatedPrincipal(subject.get().getId(), resolvedTenantId, Role.STUDENT.name(),
				parsedToken.sessionId(), parsedToken.actorUserId());
	}

	/** Paths an account that must change its password can still use. */
	private static final Set<String> PASSWORD_CHANGE_ALLOWED_POSTS = Set.of("/api/v1/auth/password",
			"/api/v1/auth/logout", "/api/v1/auth/refresh");

	private static boolean allowedDuringPasswordChange(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return ("POST".equalsIgnoreCase(request.getMethod()) && PASSWORD_CHANGE_ALLOWED_POSTS.contains(uri))
				|| uri.startsWith("/api/v1/public/");
	}

	/** Set while resolving the principal from the already-loaded account (no second lookup). */
	private static final String MUST_CHANGE_PASSWORD_ATTRIBUTE = "lms.auth.mustChangePassword";

	private AuthenticatedPrincipal resolveTenantPrincipal(ParsedToken parsedToken, HttpServletRequest request) {
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
		request.setAttribute(MUST_CHANGE_PASSWORD_ATTRIBUTE, user.isMustChangePassword());
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
