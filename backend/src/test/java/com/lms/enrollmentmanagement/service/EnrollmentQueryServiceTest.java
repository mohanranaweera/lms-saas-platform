package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Mockito-only unit coverage for {@link EnrollmentQueryService#listMyEnrolledCourseSummaries()}
 * (MVP-013, "My Courses" course-name resolution), mirroring {@code
 * EnrollmentActivationServiceTest}/{@code EnrollmentExpiryServiceTest}'s
 * established Mockito-based style for this service layer. {@link
 * EnrollmentExpiryService} is unused by this method so it is not mocked here
 * (mirrors this test's own narrow scope, not the class's full constructor
 * surface).
 *
 * <p>Resolution is a single batched {@link CourseLookupApi#getCourseSummaries(Set)}
 * call, never one call per distinct enrolled course id (fixes an N+1 query
 * pattern) - every test below asserts the batched method is invoked exactly
 * once regardless of how many enrollment rows are present.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollmentQueryServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private EnrollmentExpiryService enrollmentExpiryService;

	@Mock
	private CourseLookupApi courseLookupApi;

	private EnrollmentQueryService service;

	@BeforeEach
	void setUp() {
		service = new EnrollmentQueryService(enrollmentRepository, enrollmentExpiryService, courseLookupApi);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(STUDENT_ID, TENANT_ID, "STUDENT", UUID.randomUUID()));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void zeroCurrentEnrollmentsReturnsAnEmptyListWithoutCallingCourseLookup() {
		when(enrollmentRepository.findAllCurrentByStudentId(STUDENT_ID)).thenReturn(List.of());
		when(courseLookupApi.getCourseSummaries(Set.of())).thenReturn(List.of());

		List<CourseSummary> result = service.listMyEnrolledCourseSummaries();

		assertThat(result).isEmpty();
		verify(courseLookupApi, times(1)).getCourseSummaries(Set.of());
	}

	@Test
	void oneCurrentEnrollmentWhoseCourseResolvesReturnsExactlyOneCorrectRow() {
		UUID courseId = UUID.randomUUID();
		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, courseId, UUID.randomUUID(),
				null);
		CourseSummary summary = new CourseSummary(courseId, "Algebra I", "algebra-i", "Mathematics");
		when(enrollmentRepository.findAllCurrentByStudentId(STUDENT_ID)).thenReturn(List.of(enrollment));
		when(courseLookupApi.getCourseSummaries(Set.of(courseId))).thenReturn(List.of(summary));

		List<CourseSummary> result = service.listMyEnrolledCourseSummaries();

		assertThat(result).containsExactly(summary);
		verify(courseLookupApi, times(1)).getCourseSummaries(any());
	}

	/**
	 * A course id that fails to resolve via {@link CourseLookupApi#getCourseSummaries}
	 * (e.g. the course has since been deleted, or - defensively - belongs to
	 * another tenant) must be silently omitted from the list, never thrown as
	 * an exception that would break the whole "My Courses" page for one bad
	 * row - the batched lookup itself is trusted to omit rather than error.
	 */
	@Test
	void aCourseIdThatFailsToResolveIsSilentlyOmittedNotThrown() {
		UUID resolvableCourseId = UUID.randomUUID();
		UUID unresolvableCourseId = UUID.randomUUID();
		Enrollment resolvable = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, resolvableCourseId,
				UUID.randomUUID(), null);
		Enrollment unresolvable = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, unresolvableCourseId,
				UUID.randomUUID(), null);
		CourseSummary summary = new CourseSummary(resolvableCourseId, "Physics I", "physics-i", "Science");
		when(enrollmentRepository.findAllCurrentByStudentId(STUDENT_ID)).thenReturn(List.of(resolvable, unresolvable));
		when(courseLookupApi.getCourseSummaries(Set.of(resolvableCourseId, unresolvableCourseId)))
			.thenReturn(List.of(summary));

		List<CourseSummary> result = service.listMyEnrolledCourseSummaries();

		assertThat(result).containsExactly(summary);
		verify(courseLookupApi, times(1)).getCourseSummaries(any());
	}

	/**
	 * Two current enrollment rows for the SAME course (e.g. a historical
	 * lineage artifact, or - defensively - duplicate rows) must be collapsed
	 * into one id before the single batched lookup call - never a per-row
	 * lookup and never a duplicate entry in the id set passed to it.
	 */
	@Test
	void duplicateCourseIdsAcrossCurrentEnrollmentsAreDeduplicatedAndResolvedInOneBatchedCall() {
		UUID courseId = UUID.randomUUID();
		Enrollment first = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, courseId, UUID.randomUUID(), null);
		Enrollment second = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, courseId, UUID.randomUUID(), null);
		CourseSummary summary = new CourseSummary(courseId, "Chemistry I", "chemistry-i", "Science");
		when(enrollmentRepository.findAllCurrentByStudentId(STUDENT_ID)).thenReturn(List.of(first, second));
		when(courseLookupApi.getCourseSummaries(Set.of(courseId))).thenReturn(List.of(summary));

		List<CourseSummary> result = service.listMyEnrolledCourseSummaries();

		assertThat(result).containsExactly(summary);
		verify(courseLookupApi, times(1)).getCourseSummaries(Set.of(courseId));
	}

	/**
	 * Multiple distinct enrolled courses must still resolve via exactly one
	 * batched call - the whole point of the batching fix - never one call
	 * per distinct course id.
	 */
	@Test
	void multipleDistinctEnrolledCoursesAreResolvedInExactlyOneBatchedCall() {
		UUID courseIdA = UUID.randomUUID();
		UUID courseIdB = UUID.randomUUID();
		Enrollment enrollmentA = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, courseIdA, UUID.randomUUID(),
				null);
		Enrollment enrollmentB = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, courseIdB, UUID.randomUUID(),
				null);
		CourseSummary summaryA = new CourseSummary(courseIdA, "Algebra I", "algebra-i", "Mathematics");
		CourseSummary summaryB = new CourseSummary(courseIdB, "Biology I", "biology-i", "Science");
		when(enrollmentRepository.findAllCurrentByStudentId(STUDENT_ID))
			.thenReturn(List.of(enrollmentA, enrollmentB));
		when(courseLookupApi.getCourseSummaries(Set.of(courseIdA, courseIdB)))
			.thenReturn(List.of(summaryA, summaryB));

		List<CourseSummary> result = service.listMyEnrolledCourseSummaries();

		assertThat(result).containsExactlyInAnyOrder(summaryA, summaryB);
		verify(courseLookupApi, times(1)).getCourseSummaries(any());
	}

}
