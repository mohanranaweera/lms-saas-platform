package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped {@code content_entitlement} access (KLASS-T4b reads, KLASS-T4c
 * grant/revoke). Every finder is a {@code default} method on the inherited,
 * structurally tenant-filtered {@code findAll}/{@code findOne} - no method
 * accepts a tenant id. Rows are inserted only by {@code
 * EntitlementGrantService} (verified confirmation / approved slip) through the
 * tenant-guarded {@code save}; afterwards only the revoke columns change
 * ({@link ContentEntitlement#revoke}). Deletes are refused (append-only).
 */
public interface ContentEntitlementRepository extends AppendOnlyTenantAwareRepository<ContentEntitlement, UUID> {

	/**
	 * Every NOT-revoked entitlement of {@code studentId} for {@code courseId}
	 * (one indexed read, {@code idx_content_entitlement_tenant_student_course}).
	 * {@code valid_until} is checked by the caller against one evaluation
	 * instant ({@link ContentEntitlement#isActiveAt}).
	 */
	default List<ContentEntitlement> findUnrevokedByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId), cb.isNull(root.get("revokedAt"))));
	}

	/** The grant made for one order line, if any ({@code uq_content_entitlement_tenant_order_line}). */
	default Optional<ContentEntitlement> findByOrderLineId(UUID orderLineId) {
		return findOne((root, query, cb) -> cb.equal(root.get("orderLineId"), orderLineId));
	}

	/**
	 * The (at most one, {@code uq_content_entitlement_active_coverage}) NOT
	 * revoked entitlement of {@code studentId} for {@code coverageKey},
	 * regardless of {@code valid_until}.
	 */
	default Optional<ContentEntitlement> findUnrevokedByStudentIdAndCoverageKey(UUID studentId, String coverageKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("coverageKey"), coverageKey), cb.isNull(root.get("revokedAt"))));
	}

	/** NOT-revoked entitlements granted by one payment (chargeback revocation, D08). */
	default List<ContentEntitlement> findUnrevokedBySourcePaymentId(UUID paymentId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("sourcePaymentId"), paymentId),
				cb.isNull(root.get("revokedAt"))));
	}

	/**
	 * Transaction-scoped Postgres advisory lock (auto-released at commit /
	 * rollback) on a caller-built key - {@code CoverageKeys#lockKey}, which
	 * already contains the context tenant. Serializes grants and composer
	 * order creation for the same (tenant, student, coverage).
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireCoverageLock(@Param("lockKey") String lockKey);

}
