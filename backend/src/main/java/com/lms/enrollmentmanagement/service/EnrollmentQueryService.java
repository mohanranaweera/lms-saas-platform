package com.lms.enrollmentmanagement.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the two student-facing read endpoints named in plan §10: {@code GET
 * /api/v1/courses/{courseId}/access-state} and {@code GET
 * /api/v1/enrollments/my}.
 *
 * <h2>Why {@code /courses/{courseId}/access-state} is implemented as
 * student-only here, not "owner student or staff VIEW"</h2>
 * The plan's own API contract table (§10) labels this endpoint "Owner
 * student or staff {@code ACCESS_EXPIRY}/{@code VIEW}", mirroring {@code
 * PaymentDomainAccessGuard}'s owner-or-staff shape. That shape requires a
 * known resource owner (a {@code studentId}) to check the caller against -
 * every other owner-or-staff-gated read in this codebase resolves the
 * resource FIRST (by its own id) and only THEN authorizes against its
 * owner. This endpoint's URL carries only a {@code courseId}, never a
 * {@code studentId} - there is no student to resolve an access state FOR
 * when the caller is staff, so "or staff VIEW" cannot be implemented
 * without inventing an implicit target (e.g. "the first/any enrolled
 * student"), which would be actively wrong. This method therefore always
 * resolves the CALLING student's own state; staff have their own, correctly
 * resource-scoped read path via {@code
 * ReactivationRequestService#getQueue}/{@code #getDetail}. If a future
 * requirement genuinely needs staff to check a SPECIFIC other student's
 * access state for a course, that needs its own endpoint carrying an
 * explicit {@code studentId}, not an implicit resolution here.
 */
@Service
public class EnrollmentQueryService {

	private static final String STUDENT_ROLE = "STUDENT";

	private final EnrollmentRepository enrollmentRepository;

	private final EnrollmentExpiryService enrollmentExpiryService;

	private final CourseLookupApi courseLookupApi;

	public EnrollmentQueryService(EnrollmentRepository enrollmentRepository,
			EnrollmentExpiryService enrollmentExpiryService, CourseLookupApi courseLookupApi) {
		this.enrollmentRepository = enrollmentRepository;
		this.enrollmentExpiryService = enrollmentExpiryService;
		this.courseLookupApi = courseLookupApi;
	}

	/** See class javadoc for why this always resolves the calling student's own state. */
	@Transactional
	public EnrollmentAccessState getMyAccessState(UUID courseId) {
		AuthenticatedPrincipal principal = requireStudent();
		return enrollmentExpiryService.resolveAccessState(principal.userId(), courseId);
	}

	/** The caller's own "My Courses" list - one row per CURRENT enrollment, each enriched with its live access state. */
	@Transactional
	public List<EnrollmentSummaryView> listMyEnrollments() {
		AuthenticatedPrincipal principal = requireStudent();
		List<Enrollment> current = enrollmentRepository.findAllCurrentByStudentId(principal.userId());
		return current.stream()
			.map(enrollment -> new EnrollmentSummaryView(enrollment.getId(), enrollment.getCourseId(),
					enrollmentExpiryService.resolveAccessState(enrollment.getStudentId(), enrollment.getCourseId())))
			.toList();
	}

	/**
	 * Course-name resolution for the caller's own current enrollments
	 * (MVP-013) - no id param, mirrors listMyEnrollments()'s ownership shape
	 * exactly. Resolves every distinct enrolled course id in a single
	 * batched {@link CourseLookupApi#getCourseSummaries(Set)} call rather
	 * than one round trip per course - avoids an N+1 query pattern for a
	 * student enrolled in many courses.
	 */
	@Transactional(readOnly = true)
	public List<CourseSummary> listMyEnrolledCourseSummaries() {
		AuthenticatedPrincipal principal = requireStudent();
		Set<UUID> courseIds = enrollmentRepository.findAllCurrentByStudentId(principal.userId())
			.stream()
			.map(Enrollment::getCourseId)
			.collect(Collectors.toSet());
		return courseLookupApi.getCourseSummaries(courseIds);
	}

	private AuthenticatedPrincipal requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only a student may perform this action");
		}
		return principal;
	}

}
