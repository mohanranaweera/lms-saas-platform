package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.api.PageResponse;
import com.lms.common.persistence.BaseEntity;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CoursePriceHistory;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CoursePriceHistoryRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link CourseService} (MVP-008), matching
 * {@code usermanagement.staff.service.StaffServiceTest}'s style. Focuses on
 * the two hardest-to-get-wrong behaviors the module plan calls out: the
 * {@code course_price_history} write path being exactly-once/never-on-no-op
 * (plan §16/§18), and teacher-reassignment being Tenant-Admin-only even for
 * the course's own owning Teacher (plan §10/§15(a)) - the real HTTP-layer
 * equivalent of these lives in {@code CourseManagementIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private CoursePriceHistoryRepository coursePriceHistoryRepository;

	@Mock
	private com.lms.coursemanagement.course.repository.CourseModuleRepository courseModuleRepository;

	@Mock
	private com.lms.coursemanagement.course.repository.CourseLessonRepository courseLessonRepository;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private CourseAccessGuard courseAccessGuard;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private CourseCheckoutAmountResolver courseCheckoutAmountResolver;

	private CourseService courseService;

	@BeforeEach
	void setUp() {
		courseService = new CourseService(courseRepository, coursePriceHistoryRepository, courseModuleRepository,
				courseLessonRepository, tenantContext, permissionCheckService, userProvisioningApi, courseAccessGuard,
				eventPublisher, courseCheckoutAmountResolver);
		// A plain HashMap (never java.util.Map.of()) - a not-yet-persisted
		// Course fixture in several tests here has a null id, and Map.of()'s
		// null-key-forbidding get() would NPE on the lookup, unlike the real
		// CourseCheckoutAmountResolver's own HashMap-backed result.
		lenient().when(courseCheckoutAmountResolver.resolveBatch(any())).thenReturn(new java.util.HashMap<>());
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	// ------------------------------------------------------------------
	// changePrice / course_price_history (plan §16, §18).
	// ------------------------------------------------------------------

	@Test
	void changePriceOnAPublishedCourseWritesExactlyOneHistoryRowWithCorrectActorTenantBeforeAndAfter() {
		UUID courseId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("100.00"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));

		courseService.changePrice(courseId, new BigDecimal("150.00"));

		ArgumentCaptor<CoursePriceHistory> captor = ArgumentCaptor.forClass(CoursePriceHistory.class);
		verify(coursePriceHistoryRepository, times(1)).save(captor.capture());
		CoursePriceHistory history = captor.getValue();
		assertThat(history.getTenantId()).isEqualTo(TENANT_ID);
		assertThat(history.getCourseId()).isEqualTo(courseId);
		assertThat(history.getChangedBy()).isEqualTo(actorId);
		assertThat(history.getPreviousPrice()).isEqualByComparingTo("100.00");
		assertThat(history.getNewPrice()).isEqualByComparingTo("150.00");
		assertThat(course.getPrice()).isEqualByComparingTo("150.00");
		verify(eventPublisher, times(1)).publishEvent(any(com.lms.coursemanagement.api.CoursePriceChangedEvent.class));
	}

	@Test
	void changePriceWithTheSameValueIsATrueNoOpAndWritesNoHistoryRowOrEvent() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("100.00"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));

		courseService.changePrice(courseId, new BigDecimal("100.00"));

		verify(coursePriceHistoryRepository, never()).save(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void changePriceOnADraftCourseStillWritesTheHistoryRowUnconditionally() {
		// Decided default (plan §13): course_price_history is written on
		// EVERY price change regardless of course.status, not only once
		// published.
		UUID courseId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.DRAFT, new BigDecimal("0.00"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		courseService.changePrice(courseId, new BigDecimal("25.00"));

		ArgumentCaptor<CoursePriceHistory> captor = ArgumentCaptor.forClass(CoursePriceHistory.class);
		verify(coursePriceHistoryRepository, times(1)).save(captor.capture());
		assertThat(captor.getValue().getPreviousPrice()).isEqualByComparingTo("0.00");
		assertThat(captor.getValue().getNewPrice()).isEqualByComparingTo("25.00");
	}

	// ------------------------------------------------------------------
	// Teacher-reassignment: Tenant-Admin-only, absolute (plan §10/§15(a)).
	// ------------------------------------------------------------------

	@Test
	void reassignTeacherIsRejectedWhenActorIsTheCoursesOwnOwningTeacher() {
		UUID courseId = UUID.randomUUID();
		UUID teacherId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(teacherId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> courseService.reassignTeacher(courseId, UUID.randomUUID()))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(courseRepository);
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void reassignTeacherIsRejectedForANonTenantAdminStaffRoleEvenWithCoursesCreateEditAndApproveGrants() {
		// Course Coordinator's DomainArea.COURSES CREATE_EDIT/APPROVE grant
		// must not extend to this action - it is never checked through
		// CourseAccessGuard/PermissionCheckService at all.
		UUID courseId = UUID.randomUUID();
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "COURSE_COORDINATOR", UUID.randomUUID()));

		assertThatThrownBy(() -> courseService.reassignTeacher(courseId, UUID.randomUUID()))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(courseRepository);
		verifyNoInteractions(courseAccessGuard);
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void reassignTeacherRejectsANewTeacherIdThatIsNotARealTeacherInThisTenant() {
		UUID courseId = UUID.randomUUID();
		UUID originalTeacherId = UUID.randomUUID();
		UUID notATeacherId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.DRAFT, new BigDecimal("10.00"));
		course.setTeacherId(originalTeacherId);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(userProvisioningApi.findTenantUserSummaries(java.util.List.of(notATeacherId)))
			.thenReturn(java.util.List.of());
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));

		assertThatThrownBy(() -> courseService.reassignTeacher(courseId, notATeacherId))
			.isInstanceOf(InvalidTeacherAssignmentException.class);
		assertThat(course.getTeacherId()).isEqualTo(originalTeacherId);
	}

	// ------------------------------------------------------------------
	// Course creation: a Teacher's supplied teacherId is always overridden
	// (plan §12/§15).
	// ------------------------------------------------------------------

	@Test
	void createCourseByATeacherIgnoresAnySuppliedTeacherIdAndAlwaysUsesTheCallersOwnId() {
		UUID teacherPrincipalId = UUID.randomUUID();
		UUID suppliedOtherTeacherId = UUID.randomUUID();
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(teacherPrincipalId, TENANT_ID, "TEACHER", UUID.randomUUID()));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(courseRepository.existsBySlug("my-course")).thenReturn(false);
		when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

		NewCourseCommand command = new NewCourseCommand("My Course", "my-course", "Math", null, null, null, null, null,
				new BigDecimal("10.00"), null, null, null, suppliedOtherTeacherId);

		CourseView view = courseService.createCourse(command);

		assertThat(view.teacherId()).isEqualTo(teacherPrincipalId);
		assertThat(view.teacherId()).isNotEqualTo(suppliedOtherTeacherId);
		verifyNoInteractions(permissionCheckService);
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void createCourseByStaffRequiresPermissionAndAcceptsAValidTeacherCandidate() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
		UUID staffSuppliedTeacherId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(courseRepository.existsBySlug("staff-course")).thenReturn(false);
		when(userProvisioningApi.findTenantUserSummaries(java.util.List.of(staffSuppliedTeacherId)))
			.thenReturn(java.util.List
				.of(new com.lms.identityaccessservice.api.TenantUserSummary(staffSuppliedTeacherId, "t@example.test",
						"TEACHER", "ACTIVE")));
		when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

		NewCourseCommand command = new NewCourseCommand("Staff Course", "staff-course", "Math", null, null, null, null,
				null, new BigDecimal("10.00"), null, null, null, staffSuppliedTeacherId);

		CourseView view = courseService.createCourse(command);

		assertThat(view.teacherId()).isEqualTo(staffSuppliedTeacherId);
		verify(permissionCheckService).requirePermission(com.lms.identityaccessservice.api.DomainArea.COURSES,
				com.lms.identityaccessservice.api.PermissionAction.CREATE_EDIT);
	}

	@Test
	void createCourseByStaffRejectsATeacherIdThatDoesNotResolveToAnyTenantUserInThisTenant() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
		UUID nonexistentTeacherId = UUID.randomUUID();
		when(userProvisioningApi.findTenantUserSummaries(java.util.List.of(nonexistentTeacherId)))
			.thenReturn(java.util.List.of());

		NewCourseCommand command = new NewCourseCommand("Staff Course", "staff-course", "Math", null, null, null, null,
				null, new BigDecimal("10.00"), null, null, null, nonexistentTeacherId);

		assertThatThrownBy(() -> courseService.createCourse(command))
			.isInstanceOf(InvalidTeacherAssignmentException.class);
		verify(courseRepository, never()).save(any());
	}

	@Test
	void createCourseByStaffRejectsATeacherIdThatResolvesToANonTeacherRole() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
		UUID studentId = UUID.randomUUID();
		when(userProvisioningApi.findTenantUserSummaries(java.util.List.of(studentId)))
			.thenReturn(java.util.List.of(new com.lms.identityaccessservice.api.TenantUserSummary(studentId,
					"s@example.test", "STUDENT", "ACTIVE")));

		NewCourseCommand command = new NewCourseCommand("Staff Course", "staff-course", "Math", null, null, null, null,
				null, new BigDecimal("10.00"), null, null, null, studentId);

		assertThatThrownBy(() -> courseService.createCourse(command))
			.isInstanceOf(InvalidTeacherAssignmentException.class);
		verify(courseRepository, never()).save(any());
	}

	@Test
	void createCourseByStaffRejectsAMissingTeacherIdOutright() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));

		NewCourseCommand command = new NewCourseCommand("Staff Course", "staff-course", "Math", null, null, null, null,
				null, new BigDecimal("10.00"), null, null, null, null);

		assertThatThrownBy(() -> courseService.createCourse(command))
			.isInstanceOf(InvalidTeacherAssignmentException.class);
		verifyNoInteractions(userProvisioningApi);
		verify(courseRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// listCourses: pagination, role branching, and page-size clamping
	// (MVP-008 review Fix 4). The actual Specification predicate content
	// (status/category/teacherId filtering, tenant scoping) is proven at the
	// integration level in CourseManagementIntegrationTest - a Mockito mock
	// cannot meaningfully evaluate a JPA Criteria predicate without a real
	// EntityManager.
	// ------------------------------------------------------------------

	@Test
	void listCoursesForATeacherCallerNeverChecksPermissionAndIgnoresTheSuppliedTeacherIdFilter() {
		UUID teacherPrincipalId = UUID.randomUUID();
		UUID suppliedOtherTeacherId = UUID.randomUUID();
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(teacherPrincipalId, TENANT_ID, "TEACHER", UUID.randomUUID()));
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		courseService.listCourses(pageable, new CourseListFilter(null, null, suppliedOtherTeacherId, false));

		verifyNoInteractions(permissionCheckService);
		verify(courseRepository).findAll(any(Specification.class), any(Pageable.class));
	}

	@Test
	void listCoursesForAStaffCallerRequiresViewPermission() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		courseService.listCourses(pageable, CourseListFilter.EMPTY);

		verify(permissionCheckService).requirePermission(DomainArea.COURSES, PermissionAction.VIEW);
	}

	@Test
	void listCoursesClampsAnOversizedRequestedPageSizeToTheServerSideMaximumRegardlessOfCaller() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
		Pageable oversized = PageRequest.of(0, 999_999);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(invocation -> {
			Pageable used = invocation.getArgument(1);
			return new PageImpl<Course>(List.of(), used, 0);
		});

		courseService.listCourses(oversized, CourseListFilter.EMPTY);

		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(courseRepository).findAll(any(Specification.class), pageableCaptor.capture());
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	void listCoursesReturnsAPageResponseReflectingTheRepositoryPageMetadata() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));
		Course course = courseFixture(UUID.randomUUID(), CourseStatus.PUBLIC, new BigDecimal("10.00"));
		Pageable pageable = PageRequest.of(1, 2);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(course), pageable, 5));

		PageResponse<CourseView> result = courseService.listCourses(pageable, CourseListFilter.EMPTY);

		assertThat(result.content()).hasSize(1);
		assertThat(result.page()).isEqualTo(1);
		assertThat(result.size()).isEqualTo(2);
		assertThat(result.totalElements()).isEqualTo(5);
		assertThat(result.totalPages()).isEqualTo(3);
	}

	// ------------------------------------------------------------------
	// Resolved checkout amount exposure (Wave 2 QA gap fix) - the
	// authenticated-caller equivalent of PublicCourseView's storefront
	// fields, e.g. for a Student reading a MONTHLY/SESSION course's current
	// price via CourseAccessGuard's PUBLIC-course carve-out before checkout.
	// ------------------------------------------------------------------

	@Test
	void getCourseExposesTheResolversCheckoutAmountForAMonthlyPricedCourse() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("0.00"));
		course.setPricingModel(CoursePricingModel.MONTHLY);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseCheckoutAmountResolver.resolveBatch(List.of(course))).thenReturn(
				Map.of(courseId, new CheckoutAmount(new BigDecimal("30.00"), "LKR", UUID.randomUUID(), false, false)));

		CourseView view = courseService.getCourse(courseId);

		assertThat(view.resolvedAmount()).isEqualByComparingTo("30.00");
		assertThat(view.currency()).isEqualTo("LKR");
		assertThat(view.requiresManualQuote()).isFalse();
	}

	@Test
	void getCourseResolvedAmountIsNullRatherThanZeroWhenTheResolverHasNoAnswerYet() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("0.00"));
		course.setPricingModel(CoursePricingModel.MONTHLY);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		// Default @BeforeEach stub already returns an empty map for any input
		// - simulates a MONTHLY course with no billing period configured yet.

		CourseView view = courseService.getCourse(courseId);

		assertThat(view.resolvedAmount()).isNull();
		assertThat(view.currency()).isNull();
		assertThat(view.requiresManualQuote()).isFalse();
	}

	@Test
	void listCoursesResolvesCheckoutAmountsInOneBatchedCallAcrossTheWholePageNeverOnePerCourse() {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));
		Course courseOne = courseFixture(UUID.randomUUID(), CourseStatus.PUBLIC, new BigDecimal("10.00"));
		Course courseTwo = courseFixture(UUID.randomUUID(), CourseStatus.PUBLIC, new BigDecimal("20.00"));
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(courseOne, courseTwo), pageable, 2));

		courseService.listCourses(pageable, CourseListFilter.EMPTY);

		// The N+1 guard: exactly one batched call across the whole page,
		// never one call per course in a loop.
		verify(courseCheckoutAmountResolver, times(1)).resolveBatch(List.of(courseOne, courseTwo));
	}

	// ------------------------------------------------------------------
	// Wave 2: archive / unarchive / clone / pricing model.
	// ------------------------------------------------------------------

	@Test
	void archiveCourseSetsArchivedAtAndPublishesAnEventOnGenuineTransition() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("10.00"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		courseService.archiveCourse(courseId);

		assertThat(course.isArchived()).isTrue();
		verify(eventPublisher, times(1))
			.publishEvent(any(com.lms.coursemanagement.api.CourseArchiveStateChangedEvent.class));
	}

	@Test
	void archiveCourseIsANoOpAndPublishesNoEventWhenAlreadyArchived() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("10.00"));
		course.archive(java.time.Instant.now());
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		courseService.archiveCourse(courseId);

		verifyNoInteractions(eventPublisher);
	}

	@Test
	void unarchiveCourseClearsArchivedAt() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("10.00"));
		course.archive(java.time.Instant.now());
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		courseService.unarchiveCourse(courseId);

		assertThat(course.isArchived()).isFalse();
	}

	@Test
	void changePricingModelWritesAnEventOnGenuineChangeAndNoneOnANoOp() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("10.00"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		courseService.changePricingModel(courseId, com.lms.coursemanagement.course.domain.CoursePricingModel.SESSION);
		assertThat(course.getPricingModel())
			.isEqualTo(com.lms.coursemanagement.course.domain.CoursePricingModel.SESSION);
		verify(eventPublisher, times(1))
			.publishEvent(any(com.lms.coursemanagement.api.CoursePricingModelChangedEvent.class));

		courseService.changePricingModel(courseId, com.lms.coursemanagement.course.domain.CoursePricingModel.SESSION);
		verify(eventPublisher, times(1))
			.publishEvent(any(com.lms.coursemanagement.api.CoursePricingModelChangedEvent.class)); // still exactly once
	}

	@Test
	void cloneCourseCopiesStructureButNotPriceOrTakesADraftStatusAndZeroPriceOutsideOneTime() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CourseStatus.PUBLIC, new BigDecimal("10.00"));
		course.setPricingModel(com.lms.coursemanagement.course.domain.CoursePricingModel.SESSION);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseRepository.save(any(Course.class))).thenAnswer(inv -> inv.getArgument(0));
		when(courseModuleRepository.findByCourseId(courseId)).thenReturn(List.of());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		CourseView cloned = courseService.cloneCourse(courseId);

		assertThat(cloned.status()).isEqualTo(CourseStatus.DRAFT);
		assertThat(cloned.price()).isEqualByComparingTo("0.00");
		assertThat(cloned.pricingModel())
			.isEqualTo(com.lms.coursemanagement.course.domain.CoursePricingModel.SESSION);
		assertThat(cloned.name()).contains("(Copy)");
		verify(courseRepository, never()).delete(any(Course.class));
		// Fix 3 (Phase E architecture review, ADR-015): cloning now publishes
		// CourseClonedEvent, so AuditLogEventListener records it - previously
		// this mutation published no event at all.
		verify(eventPublisher, times(1)).publishEvent(any(com.lms.coursemanagement.api.CourseClonedEvent.class));
	}

	// ------------------------------------------------------------------
	// Fixtures.
	// ------------------------------------------------------------------

	private Course courseFixture(UUID id, CourseStatus status, BigDecimal price) {
		Course course = new Course(TENANT_ID, UUID.randomUUID(), "Test Course", "test-course-" + UUID.randomUUID(),
				"Math", null, null, null, null, null, price, null, null, status);
		setId(course, id);
		return course;
	}

	private static void setId(Course course, UUID id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(course, id);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
