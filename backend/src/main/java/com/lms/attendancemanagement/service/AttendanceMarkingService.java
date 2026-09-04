package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.repository.AttendanceRecordRepository;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.common.error.NotFoundException;
import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mark/upsert flow (plan §9/Flow A-B). Resolves {@link
 * CourseLookupApi#resolveLessonOwnership(UUID)} first (empty -&gt; {@code
 * 404}, tenant-scoped by construction), runs {@link AttendanceAccessGuard},
 * validates every submitted {@code studentId} against {@link
 * EnrollmentAccessApi#listCurrentlyEnrolledStudentIds(UUID)} before upsert,
 * derives {@code courseId} server-side (never client-supplied), and stamps
 * {@code markedBy}/{@code markedAt} from the authenticated context.
 *
 * <p>Never injects {@code EnrollmentRepository}/{@code CourseRepository} or
 * imports their entities directly - only the {@code api} packages of those
 * two modules, per {@code .claude/rules/architecture.md}.
 */
@Service
public class AttendanceMarkingService {

	private final CourseLookupApi courseLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final AttendanceAccessGuard attendanceAccessGuard;

	private final AttendanceRecordRepository attendanceRecordRepository;

	private final TenantContext tenantContext;

	public AttendanceMarkingService(CourseLookupApi courseLookupApi, EnrollmentAccessApi enrollmentAccessApi,
			AttendanceAccessGuard attendanceAccessGuard, AttendanceRecordRepository attendanceRecordRepository,
			TenantContext tenantContext) {
		this.courseLookupApi = courseLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.attendanceAccessGuard = attendanceAccessGuard;
		this.attendanceRecordRepository = attendanceRecordRepository;
		this.tenantContext = tenantContext;
	}

	/**
	 * Marks/upserts one or more students for {@code sessionId} (plan §10
	 * {@code POST .../records}). A cross-tenant or Teacher-not-owning {@code
	 * sessionId} is rejected (404/403) before ANY row is processed. Each
	 * {@code marks} row is then validated independently - a {@code
	 * studentId} not on the resolved current roster is rejected for that row
	 * only (batch-partial, plan §13), never silently dropped and never
	 * failing the whole batch.
	 */
	@Transactional
	public List<AttendanceMarkOutcome> markAttendance(UUID sessionId, List<AttendanceMarkCommand> marks) {
		LessonOwnership ownership = courseLookupApi.resolveLessonOwnership(sessionId)
			.orElseThrow(() -> new NotFoundException("Attendance session not found"));
		attendanceAccessGuard.requireSessionAccess(ownership, PermissionAction.CREATE_EDIT);

		Set<UUID> enrolledStudentIds = new HashSet<>(
				enrollmentAccessApi.listCurrentlyEnrolledStudentIds(ownership.courseId()));
		UUID markedBy = AuthenticatedPrincipalHolder.get().userId();
		Instant markedAt = Instant.now();

		List<AttendanceMarkOutcome> outcomes = new ArrayList<>(marks.size());
		for (AttendanceMarkCommand mark : marks) {
			if (!enrolledStudentIds.contains(mark.studentId())) {
				outcomes.add(AttendanceMarkOutcome.rejected(mark.studentId(),
						"Student is not currently enrolled in this course"));
				continue;
			}
			AttendanceRecord record = upsert(ownership.courseId(), sessionId, mark, markedBy, markedAt);
			outcomes.add(AttendanceMarkOutcome.success(mark.studentId(), toView(record)));
		}
		return outcomes;
	}

	/**
	 * Atomic upsert via {@link AttendanceRecordRepository#upsertRecord} - a
	 * single native {@code INSERT ... ON CONFLICT ... DO UPDATE}, fixing a
	 * TOCTOU race in the previous find-then-branch implementation (two
	 * genuinely concurrent first-time marks of the same (tenant, session,
	 * student) could both observe "no existing row" and both attempt an
	 * INSERT, one of which would then violate {@code
	 * uq_attendance_record_tenant_session_student} (V25) and surface as an
	 * unhandled {@code DataIntegrityViolationException}). The write itself no
	 * longer needs the entity object; a fresh read afterward builds the
	 * {@link AttendanceRecordView} the caller's response DTO needs.
	 */
	private AttendanceRecord upsert(UUID courseId, UUID sessionId, AttendanceMarkCommand mark, UUID markedBy,
			Instant markedAt) {
		UUID id = UuidV7Generator.generate();
		attendanceRecordRepository.upsertRecord(id, tenantContext.getTenantId(), courseId, sessionId,
				mark.studentId(), mark.status().name(), markedBy, markedAt, markedAt);
		return attendanceRecordRepository.findBySessionIdAndStudentId(sessionId, mark.studentId())
			.orElseThrow(() -> new IllegalStateException(
					"Attendance record upsert did not persist a row for session=" + sessionId + ", student="
							+ mark.studentId()));
	}

	private static AttendanceRecordView toView(AttendanceRecord record) {
		return new AttendanceRecordView(record.getId(), record.getCourseId(), record.getSessionId(),
				record.getStudentId(), record.getStatus(), record.getMarkedBy(), record.getMarkedAt(),
				record.getCreatedAt(), record.getUpdatedAt());
	}

}
