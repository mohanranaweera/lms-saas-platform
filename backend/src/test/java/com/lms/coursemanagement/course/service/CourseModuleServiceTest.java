package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.PermissionAction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link CourseModuleService} (MVP-008),
 * matching {@link CourseServiceTest}/{@link CourseAccessGuardTest}'s style.
 * Isolates this service's own branching logic - sequence-uniqueness
 * handling and parent-course ownership delegation to {@link
 * CourseAccessGuard} - without a Spring context; the real HTTP-layer
 * equivalent lives in {@code CourseModuleLessonIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class CourseModuleServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private CourseModuleRepository courseModuleRepository;

	@Mock
	private CourseAccessGuard courseAccessGuard;

	private CourseModuleService courseModuleService;

	@BeforeEach
	void setUp() {
		courseModuleService = new CourseModuleService(courseRepository, courseModuleRepository, courseAccessGuard);
	}

	@Test
	void createModuleRequiresCreateEditAccessAndPersistsWhenSequenceIsFree() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.existsByCourseIdAndSequence(courseId, 1)).thenReturn(false);
		when(courseModuleRepository.save(any(CourseModule.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CourseModuleView view = courseModuleService.createModule(courseId, "Module 1", 1);

		verify(courseAccessGuard).requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		assertThat(view.title()).isEqualTo("Module 1");
		assertThat(view.sequence()).isEqualTo(1);
	}

	@Test
	void createModuleThrowsConflictWhenSequenceAlreadyExistsInThisCourse() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.existsByCourseIdAndSequence(courseId, 1)).thenReturn(true);

		assertThatThrownBy(() -> courseModuleService.createModule(courseId, "Module 1", 1))
			.isInstanceOf(ConflictException.class);
		verify(courseModuleRepository, never()).save(any());
	}

	@Test
	void updateModuleThrowsConflictWhenTheNewSequenceCollidesWithAnotherModule() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture(courseId);
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseModuleRepository.existsByCourseIdAndSequenceAndIdNot(courseId, 2, moduleId)).thenReturn(true);

		assertThatThrownBy(() -> courseModuleService.updateModule(courseId, moduleId, "Module 1 renamed", 2))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void updateModuleKeepingTheSameSequenceNeverChecksForACollision() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture(courseId);
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));

		CourseModuleView view = courseModuleService.updateModule(courseId, moduleId, "Module 1 renamed", 1);

		assertThat(view.title()).isEqualTo("Module 1 renamed");
		verify(courseModuleRepository, never()).existsByCourseIdAndSequenceAndIdNot(any(), any(), any());
	}

	@Test
	void deleteModuleRequiresCreateEditAccessNotDelete() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture(courseId);
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));

		courseModuleService.deleteModule(courseId, moduleId);

		// Per the API contract table (plan §10), module delete is gated on
		// CREATE_EDIT for every eligible caller (staff or owning Teacher) -
		// unlike course-level delete, which is Tenant-Admin-only via DELETE.
		verify(courseAccessGuard).requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		verify(courseModuleRepository).delete(module);
	}

	@Test
	void deleteModuleThrowsNotFoundWhenTheModuleDoesNotBelongToThisCourse() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture(courseId);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> courseModuleService.deleteModule(courseId, moduleId))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void everyMethodThrowsNotFoundWhenTheParentCourseDoesNotExist() {
		UUID courseId = UUID.randomUUID();
		when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> courseModuleService.listModules(courseId)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> courseModuleService.createModule(courseId, "Module 1", 1))
			.isInstanceOf(NotFoundException.class);
	}

	/**
	 * {@code courseId} is not stamped onto the returned {@link Course}'s own
	 * {@code id} - every service method under test here takes the course id
	 * as its own parameter and never reads {@code course.getId()}, so a
	 * distinct fixture id isn't needed; {@code courseRepository.findById}
	 * is stubbed to return this exact instance for the given id, which is
	 * sufficient for {@code verify(...)}'s reference-equality check via
	 * {@code BaseEntity#equals}.
	 */
	private static Course courseFixture(UUID courseId) {
		return new Course(TENANT_ID, UUID.randomUUID(), "Test Course", "test-course", "Math", null, null, null, null,
				null, new BigDecimal("10.00"), null, null, CourseStatus.DRAFT);
	}

}
