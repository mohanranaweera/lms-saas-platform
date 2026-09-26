package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Wave 8 historical-migration proof for V56 (wave-08-plan.md §3/§8): runs the
 * REAL migration files into an isolated, throwaway schema up to V55 (the
 * pre-Wave-8 shape), seeds genuine legacy lesson-scoped {@code
 * attendance_record} rows, then applies V56 and asserts that every legacy
 * row was attached to exactly one deterministic {@code LEGACY_LESSON} sheet
 * with every original column untouched - no row orphaned, lost or
 * reinterpreted, and no {@code class_session} fabricated. Also proves the
 * pre-check aborts the migration on an inconsistent legacy course id rather
 * than silently choosing one.
 *
 * <p>Reference rows (tenant/users/course/module/lessons) are created through
 * the normal API in the main schema and copied column-for-column into the
 * throwaway schema - legal because none of those tables changes shape in V56.
 */
class AttendanceSheetMigrationIntegrationTest extends AttendanceManagementTestSupport {

	@Autowired
	private DataSource dataSource;

	@Test
	void v56AttachesEveryLegacyRecordToOneDeterministicLegacyLessonSheetWithoutChangingIt() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mig-ok");
		CourseLessonResponse lesson2 = createLessonOrFail(fixture.host(), fixture.adminToken(), fixture.course().id(),
				fixture.moduleId(), "Lesson 2", 2);
		String schema = "att_mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try {
			migrate(schema, "55");
			copyReferenceRows(schema, fixture.tenant().getId());
			UUID tenantId = fixture.tenant().getId();
			UUID courseId = fixture.course().id();
			Instant t0 = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
			UUID r1 = insertLegacy(schema, tenantId, courseId, fixture.lessonId(), fixture.student().getId(), "PRESENT",
					fixture.teacher().getId(), t0);
			UUID r2 = insertLegacy(schema, tenantId, courseId, fixture.lessonId(), fixture.admin().getId(), "LATE",
					fixture.teacher().getId(), t0.plus(1, ChronoUnit.HOURS));
			UUID r3 = insertLegacy(schema, tenantId, courseId, lesson2.id(), fixture.student().getId(), "ABSENT",
					fixture.teacher().getId(), t0.plus(1, ChronoUnit.DAYS));

			migrate(schema, "56");

			assertThat(count(schema, "SELECT count(*) FROM %s.attendance_record")).isEqualTo(3L);
			assertThat(count(schema, "SELECT count(*) FROM %s.attendance_record WHERE sheet_id IS NULL")).isZero();
			assertThat(count(schema, "SELECT count(*) FROM %s.attendance_sheet")).isEqualTo(2L);
			assertThat(count(schema, "SELECT count(*) FROM %s.attendance_sheet WHERE source <> 'LEGACY_LESSON' "
					+ "OR class_session_id IS NOT NULL")).isZero();
			assertThat(count(schema, "SELECT count(*) FROM %s.class_session")).as("no fabricated sessions").isZero();

			Map<String, Object> sheet1 = jdbcTemplate.queryForMap(String.format(
					"SELECT id, course_id, lesson_id, created_at FROM %s.attendance_sheet WHERE lesson_id = ?", schema),
					fixture.lessonId());
			UUID expectedId = jdbcTemplate.queryForObject("SELECT md5(?::text || ?::text || 'legacy-sheet')::uuid",
					UUID.class, tenantId.toString(), fixture.lessonId().toString());
			assertThat(sheet1.get("id")).isEqualTo(expectedId);
			assertThat(sheet1.get("course_id")).isEqualTo(courseId);
			assertThat(((Timestamp) sheet1.get("created_at")).toInstant()).isEqualTo(t0);

			for (UUID recordId : List.of(r1, r2)) {
				assertThat(sheetOf(schema, recordId)).isEqualTo(expectedId);
			}
			assertThat(sheetOf(schema, r3)).isNotEqualTo(expectedId);

			Map<String, Object> row1 = jdbcTemplate.queryForMap(String.format(
					"SELECT session_id, student_id, status, marked_at FROM %s.attendance_record WHERE id = ?", schema),
					r1);
			assertThat(row1.get("session_id")).isEqualTo(fixture.lessonId());
			assertThat(row1.get("student_id")).isEqualTo(fixture.student().getId());
			assertThat(row1.get("status")).isEqualTo("PRESENT");
			assertThat(((Timestamp) row1.get("marked_at")).toInstant()).isEqualTo(t0);
		}
		finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void v56AbortsWhenALegacyRecordsCourseDisagreesWithItsLessonsCourse() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mig-mismatch");
		CourseResponse otherCourse = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("att-mig-other"), fixture.teacher().getId()));
		String schema = "att_mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try {
			migrate(schema, "55");
			copyReferenceRows(schema, fixture.tenant().getId());
			insertLegacy(schema, fixture.tenant().getId(), otherCourse.id(), fixture.lessonId(),
					fixture.student().getId(), "PRESENT", fixture.teacher().getId(), Instant.now());

			assertThatThrownBy(() -> migrate(schema, "56")).hasStackTraceContaining("V56 aborted");
			assertThat(count(schema, "SELECT count(*) FROM %s.attendance_record")).as("legacy data untouched")
				.isEqualTo(1L);
		}
		finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private void migrate(String schema, String target) {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion(target))
			.load()
			.migrate();
	}

	private void copyReferenceRows(String schema, UUID tenantId) {
		jdbcTemplate.update(String.format("INSERT INTO %s.tenant SELECT * FROM public.tenant WHERE id = ?", schema),
				tenantId);
		for (String table : List.of("tenant_user", "course", "course_module", "course_lesson")) {
			jdbcTemplate.update(String.format("INSERT INTO %s.%s SELECT * FROM public.%s WHERE tenant_id = ?", schema,
					table, table), tenantId);
		}
	}

	private UUID insertLegacy(String schema, UUID tenantId, UUID courseId, UUID lessonId, UUID studentId,
			String status, UUID markedBy, Instant at) {
		UUID id = UUID.randomUUID();
		Timestamp ts = Timestamp.from(at);
		jdbcTemplate.update(String.format("INSERT INTO %s.attendance_record (id, tenant_id, course_id, session_id, "
				+ "student_id, status, marked_by, marked_at, created_at, updated_at, created_by, updated_by) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", schema), id, tenantId, courseId, lessonId, studentId,
				status, markedBy, ts, ts, ts, markedBy, markedBy);
		return id;
	}

	private long count(String schema, String sqlTemplate) {
		return jdbcTemplate.queryForObject(String.format(sqlTemplate, schema), Long.class);
	}

	private UUID sheetOf(String schema, UUID recordId) {
		return jdbcTemplate.queryForObject(
				String.format("SELECT sheet_id FROM %s.attendance_record WHERE id = ?", schema), UUID.class, recordId);
	}

}
