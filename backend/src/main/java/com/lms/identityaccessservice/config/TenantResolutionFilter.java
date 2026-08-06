package com.lms.identityaccessservice.config;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.tenantmanagement.api.TenantLookupService;
import com.lms.tenantmanagement.api.TenantSummary;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves tenant identity exactly once, from the request's {@code Host}
 * header subdomain, and populates {@link TenantContextHolder} - the ONLY
 * production code path allowed to call {@code set(...)} (plan §9/§14). Runs
 * before any credential check, per AUTH-1's explicit acceptance criterion:
 * an unresolved OR suspended/cancelled tenant is rejected here, before the
 * request ever reaches a login handler.
 *
 * <p>Excluded entirely from {@code /api/v1/platform-admin/**} (Platform
 * Admin has no tenant/subdomain to resolve - plan §9's structural-separation
 * recommendation) and from platform-operational endpoints ({@code
 * /actuator/**}, OpenAPI docs), which are not tenant-scoped business
 * endpoints and must remain reachable regardless of the request's Host
 * header (e.g. health checks hitting the container directly).
 *
 * <p>Deliberately NOT {@code @Component}: it is registered as a bean
 * exclusively via the explicit {@code @Bean} factory method in {@link
 * SecurityFilterChainConfig}, which also wires it into Spring Security's
 * filter chain. Classpath component-scanning would additionally make
 * unrelated {@code @WebMvcTest} slices (which auto-detect any {@code
 * Filter}-typed bean) try to construct it with its full dependency graph
 * (a cross-module {@code TenantLookupService}), which those narrow slices
 * cannot satisfy and were never meant to.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

	private final TenantLookupService tenantLookupService;

	private final ObjectMapper objectMapper;

	public TenantResolutionFilter(TenantLookupService tenantLookupService, ObjectMapper objectMapper) {
		this.tenantLookupService = tenantLookupService;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.startsWith("/api/v1/platform-admin/") || path.startsWith("/actuator/")
				|| path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String subdomain = extractSubdomain(request);
		Optional<TenantSummary> tenant = tenantLookupService.findActiveTenantBySubdomain(subdomain);

		if (tenant.isEmpty()) {
			FilterApiResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN, ApiErrorCodes.TENANT_UNAVAILABLE,
					"This institute is not available. Please contact your institute administrator.");
			return;
		}

		try {
			TenantContextHolder.set(tenant.get().id());
			filterChain.doFilter(request, response);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private String extractSubdomain(HttpServletRequest request) {
		String host = request.getHeader(HttpHeaders.HOST);
		if (host == null || host.isBlank()) {
			host = request.getServerName();
		}
		if (host == null) {
			return null;
		}
		String hostWithoutPort = host.split(":")[0];
		int firstDot = hostWithoutPort.indexOf('.');
		if (firstDot <= 0) {
			return null;
		}
		return hostWithoutPort.substring(0, firstDot);
	}

}
