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
	 * ADR-037 decision 6 (amended 2026-11-21): the tenant's teachers who are
	 * "on a storefront" - their storefront is enabled AND they are live
	 * (approved + ACTIVE, the same rule host resolution uses). A fixed number
	 * of batched, tenant-scoped reads; never one lookup per teacher.
	 */
	Set<UUID> findTeachersOnStorefront();

	/**
	 * ADR-037 decision 6 (amended 2026-11-21): the teachers the institute
	 * (tenant) host's PUBLIC pages hide - {@link #findTeachersOnStorefront()}
	 * on a tenant host, always empty on a storefront host (which scopes by its
	 * own product list instead). Only for public catalog/directory reads -
	 * never checkout, the student portal, admin pages or access checks.
	 */
	default Set<UUID> instituteHiddenTeacherIds() {
		return StorefrontContextHolder.isSet() ? Set.of() : findTeachersOnStorefront();
	}

	/**
	 * The host of the single enabled, live storefront that publishes {@code
	 * courseId}; empty when none or more than one does (callers then answer
	 * with the generic 404).
	 */
	Optional<String> soleStorefrontHostPublishing(UUID courseId);

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
