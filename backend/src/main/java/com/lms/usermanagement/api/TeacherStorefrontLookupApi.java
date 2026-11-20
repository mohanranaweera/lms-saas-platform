package com.lms.usermanagement.api;

import com.lms.common.tenant.StorefrontContext;
import com.lms.common.tenant.StorefrontContextHolder;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * STORE-01 / ADR-037: narrow, read-only teacher-storefront lookups for other
 * business modules (public catalog, checkout, payment return URLs). Host
 * resolution at the edge goes through {@code
 * com.lms.common.tenant.StorefrontHostResolver} instead. Every method is
 * tenant-scoped by the resolved {@code TenantContext}; none accepts a tenant
 * id. Performs no permission check.
 */
public interface TeacherStorefrontLookupApi {

	/**
	 * Courses the storefront lists AND marks published. Callers intersect
	 * this with the tenant's own public-visibility rules. Empty for an
	 * unknown storefront.
	 */
	Set<UUID> findPublishedCourseIds(UUID storefrontId);

	/**
	 * The storefront's site origin ({@code urlTemplate} with {@code
	 * {subdomain}} replaced by {@code slug.subdomain}) - only while the
	 * storefront is enabled; empty otherwise (callers fall back to the tenant
	 * origin).
	 */
	Optional<String> enabledStorefrontOrigin(UUID storefrontId, String urlTemplate);

	/**
	 * ADR-037 decision 9 (amended): the published course ids of the current
	 * request's storefront, or empty on a tenant host (no storefront context
	 * = no filter). Student-portal LIST endpoints intersect with this; detail
	 * and access checks never do, so an entitled student keeps every course.
	 */
	default Optional<Set<UUID>> currentStorefrontCourseIds() {
		return StorefrontContextHolder.current()
			.map(StorefrontContext::storefrontId)
			.map(this::findPublishedCourseIds);
	}

}
