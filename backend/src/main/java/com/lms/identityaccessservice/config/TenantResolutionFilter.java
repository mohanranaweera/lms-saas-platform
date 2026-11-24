package com.lms.identityaccessservice.config;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.tenant.StorefrontContext;
import com.lms.common.tenant.StorefrontContextHolder;
import com.lms.common.tenant.StorefrontHostResolver;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.tenantmanagement.api.StorefrontHostResolution;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantResolution;
import com.lms.tenantmanagement.api.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves tenant identity exactly once, from the request's {@code Host}
 * header - a platform subdomain or, per ADR-021, a verified custom domain
 * ({@link TenantLookupApi#resolveByHost}) - and populates {@link TenantContextHolder} - the ONLY
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
 * (a cross-module {@code TenantLookupApi}), which those narrow slices
 * cannot satisfy and were never meant to.
 *
 * <p>Consumes {@code tenant-management}'s {@link TenantLookupApi} directly -
 * that module's own {@code api} contract for {@code TEN-3} (see its javadoc)
 * - rather than a bespoke identity-access-service-local lookup interface.
 * {@link TenantLookupApi#resolveBySubdomain(String)} deliberately applies no
 * status filtering of its own (it returns any matching tenant regardless of
 * lifecycle state), so login-eligibility ({@code trial}/{@code active} only)
 * is enforced here, immediately after resolution - an unresolved subdomain
 * and a resolved-but-not-login-eligible tenant (pending approval, suspended,
 * cancelled, rejected) are deliberately collapsed into the same generic
 * "unavailable" outcome below, so the response never distinguishes "no such
 * tenant" from "tenant exists but isn't usable" (anti-enumeration by
 * construction).
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

	private final TenantLookupApi tenantLookupApi;

	private final StorefrontHostResolver storefrontHostResolver;

	private final ObjectMapper objectMapper;

	public TenantResolutionFilter(TenantLookupApi tenantLookupApi, ObjectMapper objectMapper) {
		this(tenantLookupApi, null, objectMapper);
	}

	/**
	 * @param storefrontHostResolver STORE-01 storefront resolution; {@code null}
	 * means storefront-shaped hosts always fail closed (404).
	 */
	public TenantResolutionFilter(TenantLookupApi tenantLookupApi, StorefrontHostResolver storefrontHostResolver,
			ObjectMapper objectMapper) {
		this.tenantLookupApi = tenantLookupApi;
		this.storefrontHostResolver = storefrontHostResolver;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.startsWith("/api/v1/platform-admin/") || path.startsWith("/api/v1/tenant-registrations")
				|| path.startsWith("/actuator/") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")
				// Payment-gateway server-to-server webhook (MVP-010/PAY-2) -
				// no subdomain/JWT exists for this call at all (see
				// paymentmanagement.payment.service.PaymentConfirmationService's
				// javadoc for the full explanation). Tenant identity for this
				// path is resolved from the platform's own Payment row the
				// gateway's reference maps to, never from a Host header.
				|| path.startsWith("/api/v1/integrations/webhooks/")
				// MEDIA-INT-01: the OAuth callback is served from the platform's
				// callback host, not a tenant subdomain; its tenant comes from
				// the single-use OAuth state (OAuthConnectFlowService).
				|| path.startsWith("/api/v1/integrations/oauth/")
				// VIDEO Phase 8 / ADR-025 Amendment 1: the AOC Player calls one fixed
				// API origin with no tenant host; the tenant comes from the consumed
				// pairing/launch code row or the verified player token. Exact paths.
				|| PLAYER_CARVE_OUTS.matcher(path).matches()
				// INST-QR-01: served from the platform's connection origin, not a
				// tenant host; the tenant comes only from the connection code.
				|| INSTITUTE_CONNECTION.matcher(path).matches();
	}

	private static final java.util.regex.Pattern INSTITUTE_CONNECTION = java.util.regex.Pattern
		.compile("^/api/v1/public/institute-connections/[^/]+$");

	private static final java.util.regex.Pattern PLAYER_CARVE_OUTS = java.util.regex.Pattern.compile(
			"^/api/v1/player/(pairings/complete|exchange|sessions/[0-9a-fA-F-]{36}/(heartbeat|end))$");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String host = requestHost(request);
		// STORE-01 / ADR-037: <storefront>.<tenant-subdomain>.<platform-domain>.
		Optional<StorefrontHostResolution> storefrontHost = tenantLookupApi.resolveStorefrontHost(host);
		if (storefrontHost.isPresent()) {
			doFilterStorefront(storefrontHost.get(), request, response, filterChain);
			return;
		}
		// TEN-DOM-01 / ADR-021: a platform subdomain or a verified custom domain.
		Optional<TenantResolution> tenant = tenantLookupApi.resolveByHost(host)
			.filter(TenantResolutionFilter::isLoginEligible);

		if (tenant.isEmpty()) {
			FilterApiResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN, ApiErrorCodes.TENANT_UNAVAILABLE,
					"This institute is not available. Please contact your institute administrator.");
			return;
		}

		try {
			TenantContextHolder.set(tenant.get().tenantId());
			filterChain.doFilter(request, response);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	/**
	 * STORE-01 / ADR-037 decisions 2-3: the tenant is resolved from the second
	 * label first, with exactly the same eligibility rule and the same generic
	 * 403 as any tenant host. Only then, inside that tenant, is the first label
	 * resolved to an ENABLED storefront. An unknown, disabled or retired
	 * storefront NEVER falls back to the tenant catalog: 404 {@code
	 * STOREFRONT_NOT_FOUND}, or 404 {@code STOREFRONT_MOVED} (with the current
	 * host) for a retired slug of an enabled storefront.
	 */
	private void doFilterStorefront(StorefrontHostResolution storefrontHost, HttpServletRequest request,
			HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
		TenantResolution tenant = storefrontHost.tenant();
		if (tenant == null || !isLoginEligible(tenant)) {
			FilterApiResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN, ApiErrorCodes.TENANT_UNAVAILABLE,
					"This institute is not available. Please contact your institute administrator.");
			return;
		}
		try {
			TenantContextHolder.set(tenant.tenantId());
			StorefrontHostResolver.HostLookup lookup = (storefrontHostResolver == null)
					? StorefrontHostResolver.HostLookup.notFound()
					: storefrontHostResolver.resolveForHost(storefrontHost.storefrontLabel());
			switch (lookup.status()) {
				case FOUND -> {
					StorefrontContextHolder
						.set(new StorefrontContext(lookup.storefrontId(), lookup.teacherId(), lookup.slug()));
					filterChain.doFilter(request, response);
				}
				case MOVED -> FilterApiResponseWriter.write(response, objectMapper, HttpStatus.NOT_FOUND,
						ApiErrorCodes.STOREFRONT_MOVED, "This page has moved.", Map.of("host", lookup.movedToHost()));
				default -> FilterApiResponseWriter.write(response, objectMapper, HttpStatus.NOT_FOUND,
						ApiErrorCodes.STOREFRONT_NOT_FOUND, "This page could not be found.");
			}
		}
		finally {
			StorefrontContextHolder.clear();
			TenantContextHolder.clear();
		}
	}

	private static boolean isLoginEligible(TenantResolution resolution) {
		return resolution.status() == TenantStatus.TRIAL || resolution.status() == TenantStatus.ACTIVE;
	}

	/**
	 * The host the browser addressed; parsing belongs to {@link TenantLookupApi#resolveByHost}.
	 * {@code getServerName()} is the Host header's host unless Tomcat's RemoteIpValve
	 * ({@code server.forward-headers-strategy: native}) replaced it with {@code
	 * X-Forwarded-Host} - which it does only for requests from a trusted internal
	 * proxy (the Next dev server's /api rewrite, Nginx). TEN-DOM-01: API calls go to
	 * /api on each site's own address, so the proxy's view of the host is the one
	 * that names the tenant.
	 */
	private static String requestHost(HttpServletRequest request) {
		String host = request.getServerName();
		return (host == null || host.isBlank()) ? request.getHeader(HttpHeaders.HOST) : host;
	}

}
