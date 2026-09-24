package com.lms.auditlogmanagement.repository;

import com.lms.auditlogmanagement.domain.AuditLog;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable, optional-filter {@link Specification}s for {@link AuditLog}
 * search reads (AUDIT-3). Mirrors {@code
 * com.lms.coursemanagement.course.repository.CourseSpecifications}'s exact
 * style: private constructor, static methods, each returning {@link
 * Specification#unrestricted()} when its filter argument is {@code null}, so
 * callers can unconditionally {@code .and(...)} every filter together
 * without null-checking first. The resulting composed {@code Specification}
 * is still always AND-combined with {@code TenantAwareRepositoryImpl}'s own
 * tenant-scoping predicate by {@link AuditLogRepository}'s inherited {@code
 * findAll(Specification, Pageable)}, so no filter here can ever widen a
 * result set beyond the caller's own resolved tenant.
 */
public final class AuditLogSpecifications {

	private AuditLogSpecifications() {
	}

	public static Specification<AuditLog> occurredAtFrom(Instant from) {
		if (from == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
	}

	public static Specification<AuditLog> occurredAtTo(Instant to) {
		if (to == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to);
	}

	public static Specification<AuditLog> withAction(String action) {
		if (action == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("action"), action);
	}

	public static Specification<AuditLog> withTargetEntity(String targetEntity) {
		if (targetEntity == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("targetEntity"), targetEntity);
	}

	/**
	 * Added for Wave 3's per-student/per-teacher Activity tab ({@code GET
	 * /students/{id}/activity}, {@code GET /teachers/{id}/activity}) -
	 * always combined with {@link #withTargetEntity(String)} by {@link
	 * com.lms.auditlogmanagement.service.AuditLogQueryService#findForTarget},
	 * never used alone (a bare {@code targetId} could otherwise collide
	 * across unrelated target entities sharing the same UUID space in
	 * theory).
	 */
	public static Specification<AuditLog> withTargetId(java.util.UUID targetId) {
		if (targetId == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("targetId"), targetId);
	}

}
