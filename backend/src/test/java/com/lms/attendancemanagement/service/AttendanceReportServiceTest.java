package com.lms.attendancemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.repository.AttendanceRecordRepository;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link AttendanceReportService} (plan §18).
 * Per this codebase's established convention ({@code CourseServiceTest}'s own
 * comment), the actual {@link Specification} predicate CONTENT (which
 * columns/values a filter compiles to) is proven at the Testcontainers
 * integration level - a Mockito mock cannot meaningfully evaluate a JPA
 * Criteria predicate without a real EntityManager. Concretely, {@code
 * AttendanceSpecifications#markedBetween} is exercised end-to-end by {@code
 * AttendanceReportIntegrationTest#reportsDateRangeFilterNarrowsResultsToRecordsMarkedWithinTheRequestedWindow},
 * {@code AttendanceSpecifications#withCourseId} on the {@code /my} endpoint by
 * {@code AttendanceReportIntegrationTest#myHistoryCourseIdFilterOnlyReturnsRecordsForThatCourse},
 * and cross-tenant scoping of these same filters by {@code
 * AttendanceCrossTenantIntegrationTest}. This class instead isolates the
 * role-branching/authorization/derivation logic that decides WHICH
 * Specification gets built and whether the repository is ever queried at
 * all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceReportServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private AttendanceAccessGuard attendanceAccessGuard;

	@Mock
	private AttendanceRecordRepository attendanceRecordRepository;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private TenantContext tenantContext;

	private AttendanceReportService service;

	@BeforeEach
	void setUp() {
		service = new AttendanceReportService(courseLookupApi, enrollmentAccessApi, attendanceAccessGuard,
				attendanceRecordRepository, permissionCheckService, tenantContext);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setPrincipal(UUID userId, String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, role, UUID.randomUUID()));
	}

	@SuppressWarnings("unchecked")
	private void stubEmptyPage() {
		when(attendanceRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));
	}

	// ------------------------------------------------------------------
	// getMyHistory: owner-only, student-self-only, bypasses PermissionCheckService.
	// ------------------------------------------------------------------

	@Test
	void myHistoryReturnsOnlyTheCallersOwnRecordsAndNeverConsultsThePermissionCheckService() {
		setPrincipal(STUDENT_ID, "STUDENT");
		stubEmptyPage();

		PageResponse<AttendanceRecordView> result = service.getMyHistory(AttendanceReportFilter.EMPTY,
				PageRequest.of(0, 20));

		assertThat(result.content()).isEmpty();
		verify(attendanceRecordRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void myHistoryRejectsANonStudentCallerWithoutTouchingTheRepositoryOrPermissionCheckService() {
		setPrincipal(TEACHER_ID, "TEACHER");

		assertThatThrownBy(() -> service.getMyHistory(AttendanceReportFilter.EMPTY, PageRequest.of(0, 20)))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(attendanceRecordRepository);
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void myHistoryRejectsAFromDateAfterTheToDate() {
		setPrincipal(STUDENT_ID, "STUDENT");
		Instant to = Instant.now();
		Instant from = to.plusSeconds(3600);

		assertThatThrownBy(() -> service.getMyHistory(new AttendanceReportFilter(null, from, to),
				PageRequest.of(0, 20)))
			.isInstanceOf(InvalidDateRangeException.class);

		verifyNoInteractions(attendanceRecordRepository);
	}

	// ------------------------------------------------------------------
	// getReport: Teacher branch - explicit courseId filter.
	// ------------------------------------------------------------------

	@Test
	void teacherReportWithAnExplicitCourseIdTheyOwnIsAllowedAndNeverConsultsThePermissionCheckService() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID ownedCourseId = UUID.randomUUID();
		when(courseLookupApi.getTeacherId(ownedCourseId)).thenReturn(Optional.of(TEACHER_ID));
		stubEmptyPage();

		service.getReport(new AttendanceReportFilter(ownedCourseId, null, null), PageRequest.of(0, 20));

		verify(courseLookupApi, times(1)).getTeacherId(ownedCourseId);
		verify(attendanceRecordRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void teacherReportWithAnExplicitCourseIdOwnedByAnotherTeacherIsRejectedWith403() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID otherTeachersCourseId = UUID.randomUUID();
		when(courseLookupApi.getTeacherId(otherTeachersCourseId)).thenReturn(Optional.of(UUID.randomUUID()));

		assertThatThrownBy(() -> service.getReport(new AttendanceReportFilter(otherTeachersCourseId, null, null),
				PageRequest.of(0, 20))).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(attendanceRecordRepository);
	}

	@Test
	void teacherReportWithAnExplicitCourseIdThatDoesNotResolveAtAllIsRejectedWith404NotANullPointerOr500() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID nonexistentCourseId = UUID.randomUUID();
		when(courseLookupApi.getTeacherId(nonexistentCourseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getReport(new AttendanceReportFilter(nonexistentCourseId, null, null),
				PageRequest.of(0, 20))).isInstanceOf(NotFoundException.class);

		verifyNoInteractions(attendanceRecordRepository);
	}

	// ------------------------------------------------------------------
	// getReport: Teacher branch - no explicit courseId, owned-set derivation.
	// ------------------------------------------------------------------

	@Test
	void teacherReportWithNoExplicitCourseIdDerivesTheOwnedSetFromHistoryCoursesIntersectedWithTeacherId() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID ownedCourseId = UUID.randomUUID();
		UUID otherTeachersCourseId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(attendanceRecordRepository.findDistinctCourseIdsByTenantId(TENANT_ID))
			.thenReturn(List.of(ownedCourseId, otherTeachersCourseId));
		when(courseLookupApi.getTeacherIdsByCourseId(Set.of(ownedCourseId, otherTeachersCourseId)))
			.thenReturn(Map.of(ownedCourseId, TEACHER_ID, otherTeachersCourseId, UUID.randomUUID()));
		stubEmptyPage();

		service.getReport(AttendanceReportFilter.EMPTY, PageRequest.of(0, 20));

		verify(attendanceRecordRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
		// Proves the N+1 fix: a single batched read, never a per-course-id loop.
		verify(courseLookupApi, times(1)).getTeacherIdsByCourseId(Set.of(ownedCourseId, otherTeachersCourseId));
		verify(courseLookupApi, never()).getTeacherId(any());
	}

	@Test
	void teacherReportWithNoExplicitCourseIdAndNoOwnedCoursesReturnsAnEmptyPageWithoutQueryingTheRepository() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID otherTeachersCourseId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(attendanceRecordRepository.findDistinctCourseIdsByTenantId(TENANT_ID))
			.thenReturn(List.of(otherTeachersCourseId));
		when(courseLookupApi.getTeacherIdsByCourseId(Set.of(otherTeachersCourseId)))
			.thenReturn(Map.of(otherTeachersCourseId, UUID.randomUUID()));

		PageResponse<AttendanceRecordView> result = service.getReport(AttendanceReportFilter.EMPTY,
				PageRequest.of(0, 20));

		assertThat(result.content()).isEmpty();
		verify(attendanceRecordRepository, never()).findAll(any(Specification.class), any(Pageable.class));
		verify(courseLookupApi, never()).getTeacherId(any());
	}

	@Test
	void teacherReportWithNoExplicitCourseIdSilentlyOmitsACourseIdMissingFromTheBatchedResult() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID ownedCourseId = UUID.randomUUID();
		UUID goneCourseId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(attendanceRecordRepository.findDistinctCourseIdsByTenantId(TENANT_ID))
			.thenReturn(List.of(ownedCourseId, goneCourseId));
		// goneCourseId is absent from the batched result entirely (e.g. the
		// course was deleted or never existed in this tenant) - must be
		// silently omitted, never an error.
		when(courseLookupApi.getTeacherIdsByCourseId(Set.of(ownedCourseId, goneCourseId)))
			.thenReturn(Map.of(ownedCourseId, TEACHER_ID));
		stubEmptyPage();

		service.getReport(AttendanceReportFilter.EMPTY, PageRequest.of(0, 20));

		verify(attendanceRecordRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
	}

	// ------------------------------------------------------------------
	// getReport: staff (non-Teacher) branch - tenant-wide, no ownership check.
	// ------------------------------------------------------------------

	@Test
	void staffReportRequiresTheAttendanceViewGrantAndNeverConsultsCourseLookupApi() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		stubEmptyPage();

		service.getReport(AttendanceReportFilter.EMPTY, PageRequest.of(0, 20));

		verify(permissionCheckService, times(1)).requirePermission(DomainArea.ATTENDANCE, PermissionAction.VIEW);
		verifyNoInteractions(courseLookupApi);
		verify(attendanceRecordRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
	}

	@Test
	void staffReportIsDeniedWhenTheCallerLacksTheAttendanceDomainGrant() {
		setPrincipal(UUID.randomUUID(), "COURSE_COORDINATOR");
		doThrow(new AccessDeniedException("You do not have permission to perform this action"))
			.when(permissionCheckService)
			.requirePermission(DomainArea.ATTENDANCE, PermissionAction.VIEW);

		assertThatThrownBy(() -> service.getReport(AttendanceReportFilter.EMPTY, PageRequest.of(0, 20)))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(attendanceRecordRepository);
	}

	@Test
	void reportDateRangeValidationRunsBeforeAnyRoleBranchingOrRepositoryAccess() {
		setPrincipal(TEACHER_ID, "TEACHER");
		Instant to = Instant.now();
		Instant from = to.plusSeconds(3600);

		assertThatThrownBy(
				() -> service.getReport(new AttendanceReportFilter(null, from, to), PageRequest.of(0, 20)))
			.isInstanceOf(InvalidDateRangeException.class);

		verifyNoInteractions(attendanceRecordRepository);
		verifyNoInteractions(courseLookupApi);
	}

	@Test
	void reportClampsAnOversizedRequestedPageSizeToTheServerSideMaximumRegardlessOfCaller() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		when(attendanceRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenAnswer(invocation -> {
				Pageable used = invocation.getArgument(1);
				return new PageImpl<AttendanceRecord>(List.of(), used, 0);
			});

		service.getReport(AttendanceReportFilter.EMPTY, PageRequest.of(0, 999_999));

		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(attendanceRecordRepository).findAll(any(Specification.class), pageableCaptor.capture());
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
	}

}
