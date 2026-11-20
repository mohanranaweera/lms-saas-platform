package com.lms.attendancemanagement.repository;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.common.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded attendance-report projection. The occurrence instant is sourced from
 * the authoritative teaching-session schedule when present, falling back to a
 * legacy lesson schedule and finally the mark timestamp for old unscheduled
 * records. Tenant scope is taken only from {@link TenantContext}.
 */
@Repository
public class AttendanceReportFactRepository {

	private final jakarta.persistence.EntityManager entityManager;

	private final TenantContext tenantContext;

	public AttendanceReportFactRepository(jakarta.persistence.EntityManager entityManager, TenantContext tenantContext) {
		this.entityManager = entityManager;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public List<AttendanceReportFact> findByCourseAndOccurrenceBetween(UUID courseId, Instant fromInclusive,
			Instant toExclusive, int maxFacts) {
		UUID tenantId = tenantContext.getTenantId();
		String sql = """
				SELECT ar.id, ar.student_id, ar.status, ar.sheet_id,
				       COALESCE(cs.scheduled_start, cl.starts_at, ar.marked_at) AS occurred_at
				FROM attendance_record ar
				JOIN attendance_sheet ash ON ash.tenant_id = ar.tenant_id AND ash.id = ar.sheet_id
				LEFT JOIN class_session cs ON cs.tenant_id = ash.tenant_id AND cs.id = ash.class_session_id
				LEFT JOIN course_lesson cl ON cl.tenant_id = ash.tenant_id AND cl.id = ash.lesson_id
				WHERE ar.tenant_id = :tenantId
				  AND ar.course_id = :courseId
				  AND (ash.class_session_id IS NULL OR cs.status <> 'CANCELLED')
				  AND COALESCE(cs.scheduled_start, cl.starts_at, ar.marked_at) >= :fromInclusive
				  AND COALESCE(cs.scheduled_start, cl.starts_at, ar.marked_at) < :toExclusive
				ORDER BY occurred_at, ar.id
				""";
		List<Object[]> rows = entityManager.createNativeQuery(sql)
			.setParameter("tenantId", tenantId)
			.setParameter("courseId", courseId)
			.setParameter("fromInclusive", fromInclusive)
			.setParameter("toExclusive", toExclusive)
			.setMaxResults(Math.max(1, maxFacts + 1))
			.getResultList();
		return rows.stream().map(row -> new AttendanceReportFact((UUID) row[0], (UUID) row[1], (String) row[2],
				(UUID) row[3], toInstant(row[4]))).toList();
	}

	private static Instant toInstant(Object value) {
		if (value instanceof Instant instant) {
			return instant;
		}
		if (value instanceof OffsetDateTime dateTime) {
			return dateTime.toInstant();
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp.toInstant();
		}
		return ((java.util.Date) value).toInstant();
	}

}
