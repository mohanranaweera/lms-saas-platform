package com.lms.attendancemanagement.repository;

import com.lms.attendancemanagement.domain.AttendanceSheet;
import com.lms.attendancemanagement.domain.AttendanceSheetSource;
import com.lms.common.persistence.TenantAwareRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}; never referenced outside
 * {@code com.lms.attendancemanagement}. Finders are {@code default} methods on
 * the inherited, structurally tenant-filtered {@code findOne}/{@code findAll}.
 *
 * <p>Sheets are created ONLY through the two race-safe native inserts below
 * ({@code INSERT ... ON CONFLICT DO NOTHING} against V56's partial unique
 * indexes), followed by a re-read. Two concurrent first marks of the same
 * session therefore both end up reading the SAME sheet - the second insert
 * waits for the first transaction, then no-ops. Both native inserts take an
 * explicit {@code tenantId}, guarded by {@link AttendanceTenantAssertions};
 * every caller MUST pass {@code TenantContext#getTenantId()}.
 */
public interface AttendanceSheetRepository extends TenantAwareRepository<AttendanceSheet, UUID> {

	default Optional<AttendanceSheet> findByClassSessionId(UUID classSessionId) {
		return findOne((root, query, cb) -> cb.equal(root.get("classSessionId"), classSessionId));
	}

	default Optional<AttendanceSheet> findLegacyByLessonId(UUID lessonId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("source"), AttendanceSheetSource.LEGACY_LESSON)));
	}

	default void insertClassSessionSheetIfAbsent(UUID id, UUID tenantId, UUID courseId, UUID classSessionId,
			UUID actorId, Instant now) {
		AttendanceTenantAssertions.assertTenantIdMatchesContext(tenantId, "attendance_sheet");
		insertClassSessionSheetIfAbsentUnchecked(id, tenantId, courseId, classSessionId, actorId, now);
	}

	@Modifying
	@Query(value = """
			INSERT INTO attendance_sheet
			    (id, tenant_id, course_id, source, class_session_id, lesson_id,
			     created_at, updated_at, created_by, updated_by)
			VALUES
			    (:id, :tenantId, :courseId, 'CLASS_SESSION', :classSessionId, NULL,
			     :now, :now, :actorId, :actorId)
			ON CONFLICT (tenant_id, class_session_id) WHERE class_session_id IS NOT NULL
			DO NOTHING
			""", nativeQuery = true)
	void insertClassSessionSheetIfAbsentUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("courseId") UUID courseId, @Param("classSessionId") UUID classSessionId,
			@Param("actorId") UUID actorId, @Param("now") Instant now);

	default void insertLegacySheetIfAbsent(UUID id, UUID tenantId, UUID courseId, UUID lessonId, UUID actorId,
			Instant now) {
		AttendanceTenantAssertions.assertTenantIdMatchesContext(tenantId, "attendance_sheet");
		insertLegacySheetIfAbsentUnchecked(id, tenantId, courseId, lessonId, actorId, now);
	}

	@Modifying
	@Query(value = """
			INSERT INTO attendance_sheet
			    (id, tenant_id, course_id, source, class_session_id, lesson_id,
			     created_at, updated_at, created_by, updated_by)
			VALUES
			    (:id, :tenantId, :courseId, 'LEGACY_LESSON', NULL, :lessonId,
			     :now, :now, :actorId, :actorId)
			ON CONFLICT (tenant_id, lesson_id) WHERE source = 'LEGACY_LESSON'
			DO NOTHING
			""", nativeQuery = true)
	void insertLegacySheetIfAbsentUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("courseId") UUID courseId, @Param("lessonId") UUID lessonId, @Param("actorId") UUID actorId,
			@Param("now") Instant now);

}
