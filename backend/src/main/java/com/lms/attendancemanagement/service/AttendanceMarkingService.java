package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.domain.AttendanceSheet;
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
 * The legacy lesson-scoped mark/upsert flow (MVP-016 plan §9/Flow A-B) -
 * <b>deprecated since Wave 8</b> in favor of {@link
 * AttendanceClassSessionService}, but kept fully functional because its REST
 * contract ({@code POST /attendance/sessions/{lessonId}/records}) is an
 * approved API contract (root {@code CLAUDE.md} change controls;
 * wave-08-plan.md §1.2/§10.2). Resolves {@link
 * CourseLookupApi#resolveLessonOwnership(UUID)} first (empty -&gt; {@code
 * 404}, tenant-scoped by construction), runs {@link AttendanceAccessGuard},
 * validates every submitted {@code studentId} against {@link
 * EnrollmentAccessApi#listCurrentlyEnrolledStudentIds(UUID)} before upsert,
 * derives {@code courseId} server-side (never client-supplied), and stamps
 * {@code markedBy}/{@code markedAt} from the authenticated context.
 *
 * <p>Wave 8 (V56): every row is attached to the lesson's single
 * {@code LEGACY_LESSON} {@link AttendanceSheet} (found or created, race-safe,
 * on the first valid row), so rows written through this path are never
 * orphaned either.
 */
@Service
public class AttendanceMarkingService {

	private final CourseLookupApi courseLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final AttendanceAccessGuard attendanceAccessGuard;

	private final AttendanceRecordRepository attendanceRecordRepository;

	private final AttendanceSheetService attendanceSheetService;

	private final TenantContext tenantContext;

	public AttendanceMarkingService(CourseLookupApi courseLookupApi, EnrollmentAccessApi enrollmentAccessApi,
			AttendanceAccessGuard attendanceAccessGuard, AttendanceRecordRepository attendanceRecordRepository,
			AttendanceSheetService attendanceSheetService, TenantContext tenantContext) {
		this.courseLookupApi = courseLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.attendanceAccessGuard = attendanceAccessGuard;
		this.attendanceRecordRepository = attendanceRecordRepository;
		this.attendanceSheetService = attendanceSheetService;
		this.tenantContext = tenantContext;
	}

	/**
	 * Marks/upserts one or more students for lesson {@code sessionId}. A
	 * cross-tenant or Teacher-not-owning {@code sessionId} is rejected
	 * (404/403) before ANY row is processed. Each {@code marks} row is then
	 * validated independently - a {@code studentId} not on the resolved
	 * current roster is rejected for that row only (batch-partial, plan §13).
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

		AttendanceSheet sheet = null;
		List<AttendanceMarkOutcome> outcomes = new ArrayList<>(marks.size());
		for (AttendanceMarkCommand mark : marks) {
			if (!enrolledStudentIds.contains(mark.studentId())) {
				outcomes.add(AttendanceMarkOutcome.rejected(mark.studentId(),
						"Student is not currently enrolled in this course"));
				continue;
			}
			if (sheet == null) {
				sheet = attendanceSheetService.ensureLegacySheet(ownership.courseId(), sessionId, markedBy, markedAt);
			}
			AttendanceRecord record = upsert(sheet, sessionId, mark, markedBy, markedAt);
			outcomes.add(AttendanceMarkOutcome.success(mark.studentId(),
					AttendanceRecordViewAssembler.toView(record, sheet, null)));
		}
		return outcomes;
	}

	/**
	 * Atomic upsert via {@link AttendanceRecordRepository#upsertLegacyRecord}
	 * - a single native {@code INSERT ... ON CONFLICT ... DO UPDATE} (the
	 * original TOCTOU fix). A fresh read afterward builds the view.
	 */
	private AttendanceRecord upsert(AttendanceSheet sheet, UUID lessonId, AttendanceMarkCommand mark, UUID markedBy,
			Instant markedAt) {
		UUID id = UuidV7Generator.generate();
		attendanceRecordRepository.upsertLegacyRecord(id, tenantContext.getTenantId(), sheet.getId(),
				sheet.getCourseId(), lessonId, mark.studentId(), mark.status().name(), markedBy, markedAt, markedAt);
		return attendanceRecordRepository.findBySessionIdAndStudentId(lessonId, mark.studentId())
			.orElseThrow(() -> new IllegalStateException("Attendance record upsert did not persist a row for session="
					+ lessonId + ", student=" + mark.studentId()));
	}

}
