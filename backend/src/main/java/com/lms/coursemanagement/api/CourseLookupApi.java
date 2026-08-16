package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The narrow, read-only contract other domains (future {@code
 * content-management}, {@code enrollment-management}) are permitted to
 * depend on - mirroring {@code tenantmanagement.api.TenantLookupApi}'s shape
 * for a different concern. Added now per the module plan's recommendation
 * even though no real consumer exists yet - kept deliberately minimal (three
 * narrow read methods only), not over-built.
 *
 * <p>Every method here is tenant-scoped through the same resolved {@link
 * com.lms.common.tenant.TenantContext} every other read in this codebase
 * uses - there is no overload that accepts a caller-supplied tenant id. A
 * {@code courseId} that does not resolve to a course in the caller's own
 * tenant is treated identically to a nonexistent id (empty/false), never a
 * distinguishing result.
 */
public interface CourseLookupApi {

	/**
	 * @return {@code true} only if the course exists in the caller's tenant
	 * and its status is {@code PUBLIC}; {@code false} for a nonexistent,
	 * DRAFT, PRIVATE, or cross-tenant id.
	 */
	boolean isPublished(UUID courseId);

	/**
	 * @return the course's assigned teacher id, or {@link Optional#empty()}
	 * if the course does not exist in the caller's tenant.
	 */
	Optional<UUID> getTeacherId(UUID courseId);

	/**
	 * @return the course's current price snapshot, or {@link Optional#empty()}
	 * if the course does not exist in the caller's tenant. Not a historical
	 * read - callers needing price-change history are out of this contract's
	 * scope at MVP (see {@code CoursePriceHistoryRepository}'s javadoc).
	 */
	Optional<BigDecimal> getCurrentPrice(UUID courseId);

}
