package com.lms.attendancemanagement.repository;

import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Wave 8 attendance-percentage aggregation - a single {@code GROUP BY} over
 * {@code attendance_record} computed in the database (master instruction:
 * "do not load all ... attendance records into memory to compute a
 * dashboard"). Backed by V25's tenant-leading {@code (tenant_id, course_id,
 * marked_at)} / {@code (tenant_id, student_id, marked_at)} indexes.
 *
 * <p>Tenant isolation: the {@code tenantId} predicate is ALWAYS read from the
 * trusted {@link TenantContext} inside this class - no public method accepts
 * a tenant id - and additionally re-asserted via {@link
 * AttendanceTenantAssertions}. Never referenced outside this domain.
 */
@Repository
public class AttendanceSummaryRepository {

	/** One aggregated (student, course) row. {@code total} = present + late + absent. */
	public record SummaryRow(UUID studentId, UUID courseId, long present, long late, long absent, long total) {
	}

	private static final String SELECT = """
			SELECT a.studentId, a.courseId,
			    SUM(CASE WHEN a.status = com.lms.attendancemanagement.domain.AttendanceStatus.PRESENT THEN 1 ELSE 0 END),
			    SUM(CASE WHEN a.status = com.lms.attendancemanagement.domain.AttendanceStatus.LATE THEN 1 ELSE 0 END),
			    SUM(CASE WHEN a.status = com.lms.attendancemanagement.domain.AttendanceStatus.ABSENT THEN 1 ELSE 0 END),
			    COUNT(a)
			FROM AttendanceRecord a
			WHERE a.tenantId = :tenantId
			""";

	@PersistenceContext
	private EntityManager entityManager;

	private final TenantContext tenantContext;

	public AttendanceSummaryRepository(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	/** Per-student totals for one course (staff/teacher summary). */
	public List<SummaryRow> summarizeCourseByStudent(UUID courseId, Instant from, Instant to) {
		return run(courseId, null, from, to);
	}

	/** Per-course totals for one student (student's own summary). */
	public List<SummaryRow> summarizeStudentByCourse(UUID studentId, Instant from, Instant to) {
		return run(null, studentId, from, to);
	}

	private List<SummaryRow> run(UUID courseId, UUID studentId, Instant from, Instant to) {
		UUID tenantId = tenantContext.getTenantId();
		AttendanceTenantAssertions.assertTenantIdMatchesContext(tenantId, "attendance_record");

		StringBuilder jpql = new StringBuilder(SELECT);
		if (courseId != null) {
			jpql.append(" AND a.courseId = :courseId");
		}
		if (studentId != null) {
			jpql.append(" AND a.studentId = :studentId");
		}
		if (from != null) {
			jpql.append(" AND a.markedAt >= :from");
		}
		if (to != null) {
			jpql.append(" AND a.markedAt <= :to");
		}
		jpql.append(" GROUP BY a.studentId, a.courseId");

		TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
		query.setParameter("tenantId", tenantId);
		if (courseId != null) {
			query.setParameter("courseId", courseId);
		}
		if (studentId != null) {
			query.setParameter("studentId", studentId);
		}
		if (from != null) {
			query.setParameter("from", from);
		}
		if (to != null) {
			query.setParameter("to", to);
		}
		return query.getResultList()
			.stream()
			.map(row -> new SummaryRow((UUID) row[0], (UUID) row[1], toLong(row[2]), toLong(row[3]), toLong(row[4]),
					toLong(row[5])))
			.toList();
	}

	private static long toLong(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

}
