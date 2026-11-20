package com.lms.coursemanagement.api;

import com.lms.common.api.PageResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * KLASS-T4b (plan §4.2, CM-S01/EP-E01): the paged student course catalog read
 * for enrollment-management's {@code GET /api/v1/enrollments/my/catalog}.
 * Tenant-scoped through {@link com.lms.common.tenant.TenantContext} (the
 * course repository's structural tenant filter); no method accepts a tenant
 * id. Never returns DRAFT courses and never staff-only fields (commission).
 */
public interface CourseCatalogApi {

	enum CatalogSort {

		/** {@code display_order} (nulls last), then title (case-insensitive), then id. */
		DEFAULT,

		/** Title (case-insensitive), then id. */
		TITLE

	}

	/**
	 * @param restrictToCourseIds when non-null, only these course ids (ENROLLED
	 * view; an empty set yields an empty page).
	 * @param excludeCourseIds course ids to leave out (AVAILABLE view: the
	 * caller's own participations).
	 * @param availableOnly {@code true} = PUBLIC, not archived and registration
	 * enabled only; {@code false} = any non-DRAFT course.
	 * @param search case-insensitive "contains" on title or primary teacher name.
	 * @param tagId only courses carrying this tag (a cross-tenant tag id simply
	 * matches nothing).
	 */
	record CatalogQuery(Set<UUID> restrictToCourseIds, Set<UUID> excludeCourseIds, boolean availableOnly,
			String search, UUID tagId, CatalogSort sort, int page, int size) {

	}

	record CatalogTag(UUID id, String name) {

	}

	/**
	 * @param priceAmount ONE_TIME price or MONTHLY current period amount;
	 * {@code null} for other pricing models / unconfigured billing.
	 * @param pricePeriod {@code "MONTH"} for MONTHLY, else {@code null}.
	 */
	record CatalogEntry(UUID courseId, String title, String teacherName, String coverImageUrl,
			BigDecimal priceAmount, String priceCurrency, String pricePeriod, String pricingModel,
			List<CatalogTag> tags, boolean registrationEnabled) {

	}

	PageResponse<CatalogEntry> findCatalog(CatalogQuery query);

}
