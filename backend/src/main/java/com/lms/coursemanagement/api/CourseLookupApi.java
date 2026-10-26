package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
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

	/**
	 * @return a map of {@code courseId -> teacherId} for every id in {@code
	 * courseIds} that exists in the caller's tenant, resolved via the same
	 * tenant-scoped {@link com.lms.common.tenant.TenantContext} as every
	 * other method on this interface, never a caller-supplied tenant id. A
	 * nonexistent or cross-tenant id is simply absent from the result -
	 * never an error, and never a partial-failure exception for the whole
	 * batch (same contract shape as {@link #getCourseSummaries(Set)}).
	 * Added for {@code attendance-management}'s {@code
	 * AttendanceReportService#resolveOwnedCourseIdsWithHistory} (MVP-016
	 * post-review fix) - that method previously called {@link
	 * #getTeacherId(UUID)} once per distinct course id with attendance
	 * history in the tenant, an N+1-shaped read that grows linearly with
	 * the number of such courses; this batches that resolution into a
	 * single {@code findAllById} call.
	 */
	Map<UUID, UUID> getTeacherIdsByCourseId(Set<UUID> courseIds);

	/**
	 * Resolves the checkout amount for a course per its current {@code
	 * pricing_model} (V37, Wave 2) - the single replacement for the old
	 * {@code ONE_TIME}-only {@link #getCurrentPrice(UUID)} read that {@code
	 * OrderService#createOrder} now uses. See {@link CheckoutAmount}'s own
	 * javadoc for the per-pricing-model resolution rules.
	 * @return {@link Optional#empty()} if the course does not exist in the
	 * caller's tenant (identical "not found vs. cross-tenant" non-distinction
	 * as every other method on this interface); otherwise a present {@link
	 * CheckoutAmount}.
	 * @throws CourseBillingNotConfiguredException if the course's pricing
	 * model requires a {@code course_billing_configuration}/open {@code
	 * course_billing_period} that does not exist - a genuine misconfiguration,
	 * never silently resolved to a {@code $0} amount.
	 */
	Optional<CheckoutAmount> getResolvedCheckoutAmount(UUID courseId);

	/**
	 * @return all lesson IDs belonging to courses taught by {@code teacherUserId}
	 * in the caller's tenant. Added for VIDEO Phase 6 (Q-010) - allowing teachers
	 * to view and manage media attached to courses they teach.
	 */
	Set<UUID> findLessonIdsByTeacherId(UUID teacherUserId);

	/**
	 * KLASS-T4a (for T4b/T4c): every lesson of {@code courseId} with its
	 * module and payment/schedule metadata, ordered by module sequence then
	 * lesson sequence. Two tenant-scoped batched reads (modules, then their
	 * lessons) - never one read per lesson. Empty for an unknown or
	 * cross-tenant course id.
	 */
	List<LessonPolicy> findLessonPolicies(UUID courseId);

	/**
	 * KLASS-T4a: course-level availability/pricing facts (pricing model,
	 * status, archived, registration flag, Payment Valid Days, current
	 * monthly amount). Empty for an unknown or cross-tenant course id.
	 */
	Optional<CourseCatalogInfo> findCatalogInfo(UUID courseId);

	/**
	 * KLASS-T4a (D13): the source courses linked into {@code parentCourseId}
	 * (one level only), in link creation order. Empty for an unknown or
	 * cross-tenant parent id.
	 */
	List<UUID> findLinkedSourceCourseIds(UUID parentCourseId);

	/**
	 * KLASS-T4b (D13): the parent courses {@code sourceCourseId} is linked
	 * INTO (one level only - never transitive). Used by the lesson access
	 * evaluator: participation in such a parent counts as being enrolled in
	 * the source course's lessons, which are still evaluated with the SOURCE
	 * course's own payment rules. Empty for an unknown or cross-tenant id.
	 */
	List<UUID> findLinkedParentCourseIds(UUID sourceCourseId);

	/**
	 * KLASS-T4a (D09): every module of {@code courseId} with its MODULE-offer
	 * price ({@code null} when not sold separately), in sequence order. Empty
	 * for an unknown or cross-tenant course id.
	 */
	List<ModulePriceInfo> findModulePrices(UUID courseId);


	/**
	 * R2-D6: the tags of each of {@code courseIds} (ordered by name), read in
	 * one tenant-scoped query. Courses with no tags (or unknown / cross-tenant
	 * ids) are simply absent from the map.
	 */
	Map<UUID, List<CourseTagRef>> findTagsByCourseIds(Set<UUID> courseIds);

	/**
	 * DISC-01: every course of the caller's tenant (archived included, flagged)
	 * with its current display price, ordered by name. Tenant-scoped through
	 * the resolved {@link com.lms.common.tenant.TenantContext} like every other
	 * method here; never another tenant's course.
	 */
	List<CoursePriceOption> findCoursePriceOptions();

	/**
	 * RPT-01: the current billing month ({@code billing_year}/{@code
	 * billing_month}) of each lesson in {@code lessonIds} that has one, in the
	 * caller's tenant. Lessons without a billing month, unknown and cross-tenant
	 * ids are absent. Read-only.
	 */
	Map<UUID, YearMonth> findLessonBillingMonths(Set<UUID> lessonIds);

	/** RPT-01: ids of the caller's tenant's lessons billed in one of {@code months}. */
	Set<UUID> findLessonIdsInBillingMonths(Set<YearMonth> months);

	/**
	 * RPT-02: id / name / slug / category of the caller's tenant's courses
	 * (archived and unpublished included - historical payments stay
	 * reportable), ordered by name then id, in one tenant-scoped read, bounded
	 * to the first {@link #MAX_COURSE_SUMMARIES} (the report's course picker is
	 * a type-to-filter multi-select; selected ids are validated separately, so
	 * a course beyond the bound can still be reported by id). Never another
	 * tenant's course.
	 */
	List<CourseSummary> listCourseSummaries();

	/**
	 * RPT-05: all courses whose current primary teacher is {@code teacherId},
	 * ordered for a dependent report picker. Tenant scope comes from the resolved
	 * context; a foreign teacher id returns an empty list.
	 */
	List<CourseSummary> listCourseSummariesByTeacherId(UUID teacherId);

	/** Upper bound of {@link #listCourseSummaries()}. */
	int MAX_COURSE_SUMMARIES = 2000;

}
