package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.Enrollment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;

/**
 * Tenant-scoped per ADR-006. {@code enrollment} is insert-mostly in this
 * module's scope - the only in-place mutation is the single {@code
 * superseded_at} column ({@link Enrollment#supersede()}), a narrow, justified
 * status-shaped update mirroring {@code PaymentSlipRepository}'s own
 * precedent for {@code payment_slip.status} - so (mirroring {@code
 * PaymentSlipRepository}'s exact pattern) every delete-shaped method
 * inherited from {@link TenantAwareRepository}/{@code JpaRepository} is
 * overridden below to fail loudly. No row is ever deleted.
 */
public interface EnrollmentRepository extends TenantAwareRepository<Enrollment, UUID> {

	/**
	 * Backs the idempotent-activation check for the ORIGINAL (non-
	 * reactivation) activation paths - {@code
	 * uq_enrollment_tenant_student_course_current} (V22) is the real,
	 * DB-level guarantee; this is the friendly pre-check {@code
	 * EnrollmentActivationService} uses before attempting an insert.
	 *
	 * <p>Deliberately scoped to the CURRENT row only ({@code supersededAt IS
	 * NULL}), not "any row ever" - under the lineage-row model (ADR-013), a
	 * fresh first-time activation call must still be a no-op if a current row
	 * already exists, but must NOT be blocked by a superseded historical row
	 * left over from a prior reactivation cycle (which is exactly what "any
	 * row exists" would incorrectly do).
	 */
	default boolean existsByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId), cb.isNull(root.get("supersededAt"))));
	}

	/**
	 * The lineage-aware "find the current row" read (ADR-013) - {@code
	 * supersededAt IS NULL}, matching {@code
	 * uq_enrollment_tenant_student_course_current}'s own scope exactly. This
	 * is the ONLY correct way to resolve "does this student have an
	 * enrollment for this course right now" under the lineage-row model -
	 * replaces the pre-MVP-012 {@code findByStudentIdAndCourseId}, which
	 * assumed at most one row ever existed per (student, course) and is no
	 * longer a safe assumption.
	 */
	default Optional<Enrollment> findCurrentByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId), cb.isNull(root.get("supersededAt"))));
	}

	/** Every CURRENT enrollment row for a student - backs {@code GET /api/v1/enrollments/my}. */
	default List<Enrollment> findAllCurrentByStudentId(UUID studentId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.isNull(root.get("supersededAt"))));
	}

	/**
	 * The inverse of {@link #findAllCurrentByStudentId(UUID)}, keyed by
	 * {@code courseId} instead of {@code studentId} - backs {@link
	 * com.lms.enrollmentmanagement.api.EnrollmentAccessApi
	 * #listCurrentlyEnrolledStudentIds(UUID)} (MVP-016). Unlike {@link
	 * #findAllCurrentByStudentId(UUID)}, this ALSO excludes an
	 * access-expired current row ({@code accessExpiresAt IS NULL OR
	 * accessExpiresAt > now()}) - "currently enrolled" for this consumer
	 * means access-currency, not merely lineage-currency, per that
	 * interface's own javadoc.
	 */
	default List<Enrollment> findAllCurrentByCourseId(UUID courseId) {
		Instant now = Instant.now();
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.isNull(root.get("supersededAt")),
				cb.or(cb.isNull(root.get("accessExpiresAt")), cb.greaterThan(root.<Instant>get("accessExpiresAt"), now))));
	}

	/**
	 * Reactivation idempotency pre-check (plan §9/ADR-013): {@code
	 * activating_payment_id} is set on at most one {@code enrollment} row
	 * ever (current or superseded) for a given payment, whether that row was
	 * written by a first-time activation or a reactivation. Checked BEFORE
	 * any mutation in {@code EnrollmentActivationService}'s reactivation
	 * methods, so a retried webhook/approval for an already-completed
	 * reactivation is a true no-op that never touches the prior (already
	 * superseded) row a second time.
	 */
	default boolean existsByActivatingPaymentId(UUID paymentId) {
		return exists((root, query, cb) -> cb.equal(root.get("activatingPaymentId"), paymentId));
	}

	/** The manual-slip counterpart to {@link #existsByActivatingPaymentId(UUID)}. */
	default boolean existsByActivatingSlipId(UUID slipId) {
		return exists((root, query, cb) -> cb.equal(root.get("activatingSlipId"), slipId));
	}

	/**
	 * Every {@code enrollment} row (current or superseded) whose activation
	 * evidence is set - backs {@code EnrollmentReconciliationService}'s
	 * platform-wide drift diagnostic. Deliberately cross-tenant (the {@code
	 * ...AcrossTenants} suffix, mirroring {@code
	 * PaymentRepository#findByGatewayReferenceAcrossTenants}'s ADR-006
	 * convention) - a platform-ops diagnostic, not a tenant self-service
	 * read. An explicit {@code @Query} (not a {@code default} method built on
	 * the inherited {@code Specification}-backed finders) since a bare
	 * derived-query method name ending in "AcrossTenants" is not itself
	 * parseable by Spring Data's method-name query derivation - see {@code
	 * PaymentRepository}'s identical precedent.
	 */
	@Query("SELECT e FROM Enrollment e WHERE e.activatingPaymentId IS NOT NULL OR e.activatingSlipId IS NOT NULL")
	List<Enrollment> findAllWithActivationEvidenceAcrossTenants();

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void delete(Enrollment entity) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends Enrollment> entities) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<Enrollment> entities) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

}
