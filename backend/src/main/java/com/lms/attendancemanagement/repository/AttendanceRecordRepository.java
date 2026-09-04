package com.lms.attendancemanagement.repository;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.attendancemanagement} (per {@code
 * .claude/rules/architecture.md}'s "repository is never exported outside the
 * domain package" rule). Every custom finder below is either a {@code
 * default} method built on the inherited {@link
 * org.springframework.data.jpa.domain.Specification}-backed {@code
 * findOne}/{@code findAll} (structurally tenant-scoped by {@code
 * TenantAwareRepositoryImpl}), or an explicit {@code @Query} that takes
 * {@code tenantId} as an explicit parameter - mirroring {@code
 * PaymentSlipRepository#findByIdAndTenantIdForUpdate}'s exact precedent -
 * because a custom {@code @Query} method is not automatically AND-composed
 * with the tenant predicate. Every caller of such a method MUST pass {@code
 * TenantContext#getTenantId()}, never a client-supplied value.
 *
 * <p>Both explicit-{@code tenantId} methods are additionally guarded with a
 * defense-in-depth check (post-ship review): the public {@code default}
 * method asserts the passed {@code tenantId} equals {@link
 * TenantContextHolder}'s own resolved value - throwing {@link
 * CrossTenantPersistenceException}, the exact idiom {@code
 * TenantAwareRepositoryImpl#assertOwnedByCurrentTenant} already uses for this
 * class of check - before delegating to the real {@code @Query} method. This
 * is a safety net only; every current caller already passes {@code
 * TenantContext#getTenantId()}, so it changes no currently-correct caller's
 * behavior.
 */
public interface AttendanceRecordRepository extends TenantAwareRepository<AttendanceRecord, UUID> {

	/**
	 * The (tenant, session, student) lookup key: {@code
	 * uq_attendance_record_tenant_session_student} (V25) guarantees at most
	 * one row per (tenant, session, student). Also backs {@link
	 * #findAllBySessionId} and {@code AttendanceMarkingService}'s post-{@link
	 * #upsertRecord} read - the atomic native upsert itself no longer needs
	 * this finder to decide insert vs. update (that branching now happens
	 * entirely inside the {@code ON CONFLICT} clause), but the caller still
	 * needs the full persisted row back to build its response DTO.
	 */
	default Optional<AttendanceRecord> findBySessionIdAndStudentId(UUID sessionId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("sessionId"), sessionId),
				cb.equal(root.get("studentId"), studentId)));
	}

	/** Existing marks for one session - backs the Mark Attendance roster read (plan §9/§10). */
	default List<AttendanceRecord> findAllBySessionId(UUID sessionId) {
		return findAll((root, query, cb) -> cb.equal(root.get("sessionId"), sessionId));
	}

	/**
	 * The distinct set of {@code course_id}s that have at least one
	 * attendance record in the caller's own tenant - used by {@code
	 * AttendanceReportService} to derive a Teacher caller's own-course
	 * restriction (intersected against {@code CourseLookupApi#getTeacherId}
	 * per id, since no bulk "courses owned by teacher X" read exists on
	 * {@code CourseLookupApi} - plan §9). A scalar projection, never an
	 * entity load, so it stays cheap even for a tenant with a large
	 * attendance history. Explicit {@code tenantId} param per this
	 * interface's own javadoc - the caller must always pass {@code
	 * TenantContext#getTenantId()}, never a client-supplied value.
	 *
	 * <p>Guarded by {@link #assertTenantIdMatchesContext(UUID)} - see this
	 * interface's class-level javadoc - before delegating to {@link
	 * #findDistinctCourseIdsByTenantIdUnchecked}, the actual {@code @Query}.
	 */
	default List<UUID> findDistinctCourseIdsByTenantId(UUID tenantId) {
		assertTenantIdMatchesContext(tenantId);
		return findDistinctCourseIdsByTenantIdUnchecked(tenantId);
	}

	@Query("SELECT DISTINCT a.courseId FROM AttendanceRecord a WHERE a.tenantId = :tenantId")
	List<UUID> findDistinctCourseIdsByTenantIdUnchecked(@Param("tenantId") UUID tenantId);

	/**
	 * Atomic DB-level upsert backing {@code AttendanceMarkingService}'s
	 * mark/re-mark flow - replaces a racy {@code findBySessionIdAndStudentId}
	 * -&gt;-insert-or-update sequence (a genuine TOCTOU: two concurrent
	 * first-time marks of the same (tenant, session, student) could both see
	 * "no existing row" and both attempt an INSERT, one of which would then
	 * violate {@code uq_attendance_record_tenant_session_student} (V25) and
	 * surface as an unhandled {@code DataIntegrityViolationException}) with a
	 * single native {@code INSERT ... ON CONFLICT (tenant_id, session_id,
	 * student_id) DO UPDATE}, making "at most one row per (tenant, session,
	 * student), re-mark is an in-place update" atomic at the DB level rather
	 * than a service-layer-only invariant, per {@code
	 * .claude/rules/backend.md}'s preference for schema/DB-enforced
	 * invariants on high-integrity write paths.
	 *
	 * <p>{@code id} is a freshly generated (application-side, {@code
	 * UuidV7Generator}) id used ONLY on the insert branch - on conflict, the
	 * existing row's {@code id}/{@code created_at}/{@code created_by} are
	 * left untouched (the {@code DO UPDATE} clause never references them,
	 * only {@code status}/{@code marked_by}/{@code marked_at}/{@code
	 * updated_at}/{@code updated_by} are overwritten from {@code EXCLUDED},
	 * mirroring {@link AttendanceRecord#remark}'s exact field set).
	 *
	 * <p>A native query bypasses the JPA persistence context and Hibernate's
	 * {@code Auditable} auditing listener entirely, so {@code created_by}/
	 * {@code updated_by} are set explicitly here (both {@code = markedBy} on
	 * first insert; only {@code updated_by} changes on a re-mark, via {@code
	 * EXCLUDED.updated_by}). {@code now} is bound to the same value as {@code
	 * markedAt} by every caller, so {@code created_at}/{@code updated_at} on
	 * first insert and {@code updated_at} on re-mark all agree with {@code
	 * marked_at}.
	 *
	 * <p>{@code tenantId} is passed explicitly and MUST always be {@code
	 * TenantContext#getTenantId()} - never a client-supplied value - per this
	 * interface's own javadoc; this native query is NOT automatically
	 * tenant-filtered by {@code TenantAwareRepositoryImpl} the way inherited
	 * finders are.
	 *
	 * <p>Guarded by {@link #assertTenantIdMatchesContext(UUID)} - see this
	 * interface's class-level javadoc - before delegating to {@link
	 * #upsertRecordUnchecked}, the actual native {@code @Query}.
	 */
	default void upsertRecord(UUID id, UUID tenantId, UUID courseId, UUID sessionId, UUID studentId, String status,
			UUID markedBy, Instant markedAt, Instant now) {
		assertTenantIdMatchesContext(tenantId);
		upsertRecordUnchecked(id, tenantId, courseId, sessionId, studentId, status, markedBy, markedAt, now);
	}

	@Modifying
	@Query(value = """
			INSERT INTO attendance_record
			    (id, tenant_id, course_id, session_id, student_id, status, marked_by,
			     marked_at, created_at, updated_at, created_by, updated_by)
			VALUES
			    (:id, :tenantId, :courseId, :sessionId, :studentId, :status, :markedBy,
			     :markedAt, :now, :now, :markedBy, :markedBy)
			ON CONFLICT (tenant_id, session_id, student_id)
			DO UPDATE SET
			    status = EXCLUDED.status,
			    marked_by = EXCLUDED.marked_by,
			    marked_at = EXCLUDED.marked_at,
			    updated_at = EXCLUDED.updated_at,
			    updated_by = EXCLUDED.updated_by
			""", nativeQuery = true)
	void upsertRecordUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("courseId") UUID courseId, @Param("sessionId") UUID sessionId,
			@Param("studentId") UUID studentId, @Param("status") String status, @Param("markedBy") UUID markedBy,
			@Param("markedAt") Instant markedAt, @Param("now") Instant now);

	/**
	 * Defense-in-depth guard (post-ship review) shared by {@link
	 * #upsertRecord} and {@link #findDistinctCourseIdsByTenantId}: both take
	 * {@code tenantId} as an explicit parameter (see class-level javadoc)
	 * rather than relying on {@code TenantAwareRepositoryImpl}'s structural
	 * {@code Specification} filtering, so - unlike every other tenant-scoped
	 * query in this codebase - there is no compiler/framework safety net if a
	 * future caller passes the wrong value. This asserts the passed {@code
	 * tenantId} agrees with {@link TenantContextHolder}'s own resolved value,
	 * throwing before the query executes on any mismatch. Both current call
	 * sites already pass {@code TenantContext#getTenantId()}, so this changes
	 * no currently-correct caller's behavior.
	 */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to query attendance_record using a tenantId that does not match the current tenant context");
		}
	}

}
