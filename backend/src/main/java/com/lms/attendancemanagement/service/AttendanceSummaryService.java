package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.repository.AttendanceSummaryRepository;
import com.lms.attendancemanagement.repository.AttendanceSummaryRepository.SummaryRow;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 8 attendance percentages (master instruction §22 "attendance
 * percentages where required"), aggregated in SQL by {@link
 * AttendanceSummaryRepository}. Role scoping mirrors the rest of the module:
 *
 * <ul>
 * <li>{@link #getCourseSummary} - Teacher of that course (live owner) or
 * staff with {@code ATTENDANCE}/{@code VIEW}; Student 403 before any lookup;
 * a course outside the caller's tenant 404.</li>
 * <li>{@link #getMySummary} - Student only, always the caller's own rows.</li>
 * </ul>
 */
@Service
public class AttendanceSummaryService {

	private static final String STUDENT_ROLE = "STUDENT";

	private final AttendanceSummaryRepository attendanceSummaryRepository;

	private final AttendanceAccessGuard attendanceAccessGuard;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	public AttendanceSummaryService(AttendanceSummaryRepository attendanceSummaryRepository,
			AttendanceAccessGuard attendanceAccessGuard, CourseLookupApi courseLookupApi,
			StudentLookupApi studentLookupApi) {
		this.attendanceSummaryRepository = attendanceSummaryRepository;
		this.attendanceAccessGuard = attendanceAccessGuard;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
	}

	@Transactional(readOnly = true)
	public List<AttendanceSummaryView> getCourseSummary(UUID courseId, Instant from, Instant to) {
		attendanceAccessGuard.requireClassSessionPreLookup(PermissionAction.VIEW);
		validateDateRange(from, to);
		String courseName = courseLookupApi.getCourseSummaries(Set.of(courseId))
			.stream()
			.findFirst()
			.map(CourseSummary::name)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		attendanceAccessGuard.requireCourseOwnershipIfTeacher(courseId);

		List<SummaryRow> rows = attendanceSummaryRepository.summarizeCourseByStudent(courseId, from, to);
		Map<UUID, String> names = studentLookupApi
			.getStudentSummariesByUserId(rows.stream().map(SummaryRow::studentId).toList())
			.stream()
			.collect(Collectors.toMap(StudentSummary::userId, StudentSummary::name, (a, b) -> a));
		return rows.stream()
			.map(row -> toView(row, names.get(row.studentId()), courseName))
			.sorted(Comparator.comparing(AttendanceSummaryView::studentName,
					Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<AttendanceSummaryView> getMySummary(Instant from, Instant to) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only a student may perform this action");
		}
		validateDateRange(from, to);
		List<SummaryRow> rows = attendanceSummaryRepository.summarizeStudentByCourse(principal.userId(), from, to);
		Map<UUID, String> courseNames = courseLookupApi
			.getCourseSummaries(rows.stream().map(SummaryRow::courseId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));
		return rows.stream()
			.map(row -> toView(row, null, courseNames.get(row.courseId())))
			.sorted(Comparator.comparing(AttendanceSummaryView::courseName,
					Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
			.toList();
	}

	private static AttendanceSummaryView toView(SummaryRow row, String studentName, String courseName) {
		return new AttendanceSummaryView(row.studentId(), studentName, row.courseId(), courseName, row.present(),
				row.late(), row.absent(), row.total(),
				AttendanceSummaryView.rateOf(row.present(), row.late(), row.total()));
	}

	private static void validateDateRange(Instant from, Instant to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidDateRangeException("'from' must not be after 'to'");
		}
	}

}
