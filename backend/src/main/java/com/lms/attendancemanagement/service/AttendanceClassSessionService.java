package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.domain.AttendanceSheet;
import com.lms.attendancemanagement.repository.AttendanceRecordRepository;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 8 class-session attendance workflow (master instruction §22): select
 * course -> select class session -> load authorized roster -> mark -> save.
 * {@code ClassSession -> AttendanceSheet -> AttendanceRecord}.
 *
 * <p>Every method runs, in order: {@link
 * AttendanceAccessGuard#requireClassSessionPreLookup} (Student / ungranted
 * staff denied before the id is resolved), the tenant-scoped {@link
 * ClassSessionLookupApi#findSession} (cross-tenant/unknown -> 404), then
 * {@link AttendanceAccessGuard#requireCourseOwnershipIfTeacher} (Teacher of
 * another course -> 403). {@code courseId} is always the session's own,
 * never client-supplied.
 */
@Service
public class AttendanceClassSessionService {

	static final String NOT_ENROLLED_REASON = "Student is not currently enrolled in this course";

	private final ClassSessionLookupApi classSessionLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final StudentLookupApi studentLookupApi;

	private final AttendanceAccessGuard attendanceAccessGuard;

	private final AttendanceSheetService attendanceSheetService;

	private final AttendanceRecordRepository attendanceRecordRepository;

	private final TenantContext tenantContext;

	private final Clock clock;

	public AttendanceClassSessionService(ClassSessionLookupApi classSessionLookupApi,
			EnrollmentAccessApi enrollmentAccessApi, StudentLookupApi studentLookupApi,
			AttendanceAccessGuard attendanceAccessGuard, AttendanceSheetService attendanceSheetService,
			AttendanceRecordRepository attendanceRecordRepository, TenantContext tenantContext, Clock clock) {
		this.classSessionLookupApi = classSessionLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.studentLookupApi = studentLookupApi;
		this.attendanceAccessGuard = attendanceAccessGuard;
		this.attendanceSheetService = attendanceSheetService;
		this.attendanceRecordRepository = attendanceRecordRepository;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	/**
	 * Roster = every currently-enrolled student, followed by any student who
	 * already has a mark on this session's sheet but is no longer enrolled
	 * ({@code currentlyEnrolled=false}) - so historical marks never disappear
	 * from the sheet. Never creates a sheet.
	 */
	@Transactional(readOnly = true)
	public AttendanceClassSessionRosterView getRoster(UUID classSessionId) {
		ClassSessionSummary session = resolveAuthorizedSession(classSessionId, PermissionAction.VIEW);

		Optional<AttendanceSheet> sheet = attendanceSheetService.findByClassSessionId(session.id());
		Map<UUID, AttendanceRecord> marksByStudent = sheet
			.map(s -> attendanceRecordRepository.findAllBySheetId(s.getId()))
			.orElse(List.of())
			.stream()
			.collect(Collectors.toMap(AttendanceRecord::getStudentId, record -> record));

		Set<UUID> enrolled = new LinkedHashSet<>(
				enrollmentAccessApi.listCurrentlyEnrolledStudentIds(session.courseId()));
		Set<UUID> allStudentIds = new LinkedHashSet<>(enrolled);
		allStudentIds.addAll(marksByStudent.keySet());

		Map<UUID, String> names = studentLookupApi.getStudentSummariesByUserId(allStudentIds)
			.stream()
			.collect(Collectors.toMap(StudentSummary::userId, StudentSummary::name, (a, b) -> a));

		List<AttendanceClassSessionRosterView.Entry> roster = allStudentIds.stream().map(studentId -> {
			AttendanceRecord mark = marksByStudent.get(studentId);
			return new AttendanceClassSessionRosterView.Entry(studentId, names.get(studentId),
					mark != null ? mark.getStatus() : null, enrolled.contains(studentId));
		}).toList();

		Optional<String> closedReason = ClassSessionMarkingPolicy.closedReason(session, clock.instant());
		return new AttendanceClassSessionRosterView(sheet.map(AttendanceSheet::getId).orElse(null), session.id(),
				session.courseId(), session.title(), session.scheduledStart(), session.scheduledEnd(),
				session.status(), closedReason.isEmpty(), closedReason.orElse(null), roster);
	}

	/**
	 * Marks/upserts students for one class session. Lifecycle gate first
	 * ({@link ClassSessionMarkingPolicy}, 409 when closed) - the whole batch
	 * is rejected before any row is touched. Then batch-partial per row
	 * (unchanged MVP-016 contract): a student not currently enrolled is
	 * rejected for that row only. The session's sheet is created lazily on
	 * the first valid row, so an all-rejected batch leaves no empty sheet.
	 */
	@Transactional
	public List<AttendanceMarkOutcome> markAttendance(UUID classSessionId, List<AttendanceMarkCommand> marks) {
		ClassSessionSummary session = resolveAuthorizedSession(classSessionId, PermissionAction.CREATE_EDIT);
		Instant now = clock.instant();
		ClassSessionMarkingPolicy.closedReason(session, now).ifPresent(reason -> {
			throw new ConflictException(reason);
		});

		Set<UUID> enrolled = Set.copyOf(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(session.courseId()));
		UUID markedBy = AuthenticatedPrincipalHolder.get().userId();
		UUID tenantId = tenantContext.getTenantId();

		AttendanceSheet sheet = null;
		List<AttendanceMarkOutcome> outcomes = new ArrayList<>(marks.size());
		for (AttendanceMarkCommand mark : marks) {
			if (!enrolled.contains(mark.studentId())) {
				outcomes.add(AttendanceMarkOutcome.rejected(mark.studentId(), NOT_ENROLLED_REASON));
				continue;
			}
			if (sheet == null) {
				sheet = attendanceSheetService.ensureClassSessionSheet(session, markedBy, now);
			}
			attendanceRecordRepository.upsertClassSessionRecord(UuidV7Generator.generate(), tenantId, sheet.getId(),
					sheet.getCourseId(), mark.studentId(), mark.status().name(), markedBy, now, now);
			UUID sheetId = sheet.getId();
			AttendanceRecord record = attendanceRecordRepository.findBySheetIdAndStudentId(sheetId, mark.studentId())
				.orElseThrow(() -> new IllegalStateException("Attendance record upsert did not persist a row for sheet="
						+ sheetId + ", student=" + mark.studentId()));
			outcomes.add(AttendanceMarkOutcome.success(mark.studentId(),
					AttendanceRecordViewAssembler.toView(record, sheet, session)));
		}
		return outcomes;
	}

	private ClassSessionSummary resolveAuthorizedSession(UUID classSessionId, PermissionAction action) {
		attendanceAccessGuard.requireClassSessionPreLookup(action);
		ClassSessionSummary session = classSessionLookupApi.findSession(classSessionId)
			.orElseThrow(() -> new NotFoundException("Class session not found"));
		attendanceAccessGuard.requireCourseOwnershipIfTeacher(session.courseId());
		return session;
	}

}
