package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

	/**
	 * @return the resolved module/course/teacher/publish-state for a lesson,
	 * or {@link Optional#empty()} if the lesson does not exist in the
	 * caller's tenant. Added for content-management (MVP-009) - a caller
	 * that only verifies {@link #getTeacherId(UUID)} against a
	 * client-supplied {@code courseId} cannot detect a lesson that actually
	 * belongs to a DIFFERENT course than the one named in the request path;
	 * this method resolves ownership from the lesson id itself, so the
	 * caller can cross-check every path segment against the real parent
	 * chain.
	 */
	Optional<LessonOwnership> resolveLessonOwnership(UUID lessonId);

	/**
	 * @return {@link Optional#empty()} if the course does not exist in the
	 * caller's tenant; otherwise a present {@link CourseAccessWindow} - see
	 * that record's own javadoc for why a plain {@code Optional<Integer>}
	 * cannot express this method's three real outcomes (not found / lifetime
	 * access / time-limited access), since {@code
	 * course.access_duration_days} is itself nullable (V11, "{@code NULL} =
	 * unlimited access") and a nested {@code null} cannot be represented
	 * inside a present {@code Optional}. Added for {@code
	 * enrollment-management} (MVP-012) - {@code
	 * EnrollmentActivationService} reads this once, at (re)activation time,
	 * and snapshots the result; it never re-reads this later to retroactively
	 * change an already-activated enrollment's {@code accessExpiresAt}.
	 */
	Optional<CourseAccessWindow> getAccessDurationDays(UUID courseId);

	/**
	 * @return the display summary (name/slug/category) for every id in
	 * {@code courseIds} that exists in the caller's tenant, in no particular
	 * guaranteed order - resolved via the same tenant-scoped {@link
	 * com.lms.common.tenant.TenantContext} as every other method on this
	 * interface, never a caller-supplied tenant id. A nonexistent or
	 * cross-tenant id is simply absent from the result - never an error, and
	 * never a partial-failure exception for the whole batch. Added for
	 * {@code enrollment-management}'s student-facing course-name resolution
	 * (MVP-013, "My Courses") - a student holds no grant in {@code
	 * DomainArea.COURSES}'s permission matrix, so this narrow read is the
	 * only way that page can resolve its enrolled courses' names, batched in
	 * a single call rather than one round trip per course id.
	 */
	List<CourseSummary> getCourseSummaries(Set<UUID> courseIds);

}
