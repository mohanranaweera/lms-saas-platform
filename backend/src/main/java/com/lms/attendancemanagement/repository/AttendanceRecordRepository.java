package com.lms.attendancemanagement.repository;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.common.persistence.TenantAwareRepository;
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
 * <p>Every explicit-{@code tenantId} method is additionally guarded with a
 * defense-in-depth check (post-ship review): the public {@code default}
 * method asserts the passed {@code tenantId} equals the resolved tenant
 * context ({@link AttendanceTenantAssertions}) before delegating to the real
 * {@code @Query} method. This is a safety net only; every current caller
 * already passes {@code TenantContext#getTenantId()}.
 *
 * <p><b>Wave 8 (V56).</b> Every row belongs to an {@code attendance_sheet}.
 * {@link #upsertLegacyRecord} backs the deprecated lesson-scoped endpoints
 * (writes {@code session_id} = lesson id); {@link #upsertClassSessionRecord}
 * backs the class-session workflow ({@code session_id} stays {@code NULL}).
 * Both conflict on {@code uq_attendance_record_tenant_sheet_student} - a
 * LEGACY_LESSON sheet is 1:1 with its lesson, so for legacy rows that
 * constraint coincides exactly with V25's {@code
 * uq_attendance_record_tenant_session_student}.
 *
 * <p>Both upserts are {@code @Modifying(clearAutomatically = true)}: a native
 * write bypasses the persistence context, so without clearing it a follow-up
 * {@code findBySheetIdAndStudentId} for a row already loaded earlier in the
 * same transaction (e.g. the same student twice in one batch) would return
 * the stale managed instance instead of the just-written status.
 */
public interface AttendanceRecordRepository extends TenantAwareRepository<AttendanceRecord, UUID> {

	/**
	 * The legacy (tenant, lesson, student) lookup key: {@code
	 * uq_attendance_record_tenant_session_student} (V25) guarantees at most
	 * one row per (tenant, lesson, student). Only ever matches legacy rows -
	 * class-session rows have a {@code NULL} {@code session_id}.
	 */
	default Optional<AttendanceRecord> findBySessionIdAndStudentId(UUID sessionId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("sessionId"), sessionId),
				cb.equal(root.get("studentId"), studentId)));
	}

	/** Existing marks for one legacy lesson - backs the deprecated lesson roster read. */
	default List<AttendanceRecord> findAllBySessionId(UUID sessionId) {
		return findAll((root, query, cb) -> cb.equal(root.get("sessionId"), sessionId));
	}

	/** At most one row per (tenant, sheet, student) - {@code uq_attendance_record_tenant_sheet_student} (V56). */
	default Optional<AttendanceRecord> findBySheetIdAndStudentId(UUID sheetId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("sheetId"), sheetId),
				cb.equal(root.get("studentId"), studentId)));
	}

	/** Existing marks for one sheet - backs the class-session roster read. */
	default List<AttendanceRecord> findAllBySheetId(UUID sheetId) {
		return findAll((root, query, cb) -> cb.equal(root.get("sheetId"), sheetId));
	}

	/**
	 * The distinct set of {@code course_id}s that have at least one
	 * attendance record in the caller's own tenant - used by {@code
	 * AttendanceReportService} to derive a Teacher caller's own-course
	 * restriction. A scalar projection, never an entity load.
	 */
	default List<UUID> findDistinctCourseIdsByTenantId(UUID tenantId) {
		AttendanceTenantAssertions.assertTenantIdMatchesContext(tenantId, "attendance_record");
		return findDistinctCourseIdsByTenantIdUnchecked(tenantId);
	}

	@Query("SELECT DISTINCT a.courseId FROM AttendanceRecord a WHERE a.tenantId = :tenantId")
	List<UUID> findDistinctCourseIdsByTenantIdUnchecked(@Param("tenantId") UUID tenantId);

	/**
	 * Atomic DB-level upsert for the deprecated lesson-scoped marking flow -
	 * a single native {@code INSERT ... ON CONFLICT DO UPDATE}, so two
	 * concurrent first-time marks of the same (tenant, sheet, student) can
	 * never both INSERT (the original TOCTOU fix, unchanged in spirit).
	 *
	 * <p>{@code id} is used ONLY on the insert branch - on conflict, the
	 * existing row's {@code id}/{@code created_at}/{@code created_by} are left
	 * untouched; only {@code status}/{@code marked_by}/{@code marked_at}/
	 * {@code updated_at}/{@code updated_by} are overwritten, mirroring {@link
	 * AttendanceRecord#remark}. A native query bypasses Hibernate's auditing
	 * listener, so {@code created_by}/{@code updated_by} are set explicitly.
	 */
	default void upsertLegacyRecord(UUID id, UUID tenantId, UUID sheetId, UUID courseId, UUID lessonId,
			UUID studentId, String status, UUID markedBy, Instant markedAt, Instant now) {
		AttendanceTenantAssertions.assertTenantIdMatchesContext(tenantId, "attendance_record");
		upsertLegacyRecordUnchecked(id, tenantId, sheetId, courseId, lessonId, studentId, status, markedBy, markedAt,
				now);
	}

	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO attendance_record
			    (id, tenant_id, sheet_id, course_id, session_id, student_id, status, marked_by,
			     marked_at, created_at, updated_at, created_by, updated_by)
			VALUES
			    (:id, :tenantId, :sheetId, :courseId, :lessonId, :studentId, :status, :markedBy,
			     :markedAt, :now, :now, :markedBy, :markedBy)
			ON CONFLICT (tenant_id, sheet_id, student_id)
			DO UPDATE SET
			    status = EXCLUDED.status,
			    marked_by = EXCLUDED.marked_by,
			    marked_at = EXCLUDED.marked_at,
			    updated_at = EXCLUDED.updated_at,
			    updated_by = EXCLUDED.updated_by
			""", nativeQuery = true)
	void upsertLegacyRecordUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("sheetId") UUID sheetId, @Param("courseId") UUID courseId, @Param("lessonId") UUID lessonId,
			@Param("studentId") UUID studentId, @Param("status") String status, @Param("markedBy") UUID markedBy,
			@Param("markedAt") Instant markedAt, @Param("now") Instant now);

	/**
	 * Same atomic upsert shape as {@link #upsertLegacyRecord}, for a
	 * CLASS_SESSION sheet - {@code session_id} (the legacy lesson column) is
	 * deliberately omitted and therefore {@code NULL}. {@code courseId} MUST
	 * be the sheet's own course (V56's composite FK {@code (tenant_id,
	 * sheet_id, course_id)} rejects anything else at the DB level).
	 */
	default void upsertClassSessionRecord(UUID id, UUID tenantId, UUID sheetId, UUID courseId, UUID studentId,
			String status, UUID markedBy, Instant markedAt, Instant now) {
		AttendanceTenantAssertions.assertTenantIdMatchesContext(tenantId, "attendance_record");
		upsertClassSessionRecordUnchecked(id, tenantId, sheetId, courseId, studentId, status, markedBy, markedAt, now);
	}

	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO attendance_record
			    (id, tenant_id, sheet_id, course_id, student_id, status, marked_by,
			     marked_at, created_at, updated_at, created_by, updated_by)
			VALUES
			    (:id, :tenantId, :sheetId, :courseId, :studentId, :status, :markedBy,
			     :markedAt, :now, :now, :markedBy, :markedBy)
			ON CONFLICT (tenant_id, sheet_id, student_id)
			DO UPDATE SET
			    status = EXCLUDED.status,
			    marked_by = EXCLUDED.marked_by,
			    marked_at = EXCLUDED.marked_at,
			    updated_at = EXCLUDED.updated_at,
			    updated_by = EXCLUDED.updated_by
			""", nativeQuery = true)
	void upsertClassSessionRecordUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("sheetId") UUID sheetId, @Param("courseId") UUID courseId, @Param("studentId") UUID studentId,
			@Param("status") String status, @Param("markedBy") UUID markedBy, @Param("markedAt") Instant markedAt,
			@Param("now") Instant now);

}
