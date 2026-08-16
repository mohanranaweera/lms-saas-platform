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
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
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
 * Mockito-only unit coverage for {@link CourseLessonService} (MVP-008),
 * matching {@link CourseModuleServiceTest}'s style. Isolates this service's
 * own branching logic - sequence-uniqueness handling within a module, and
 * the "module must actually belong to this course" resolution - without a
 * Spring context; the real HTTP-layer equivalent lives in {@code
 * CourseModuleLessonIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class CourseLessonServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private CourseModuleRepository courseModuleRepository;

	@Mock
	private CourseLessonRepository courseLessonRepository;

	@Mock
	private CourseAccessGuard courseAccessGuard;

	private CourseLessonService courseLessonService;

	@BeforeEach
	void setUp() {
		courseLessonService = new CourseLessonService(courseRepository, courseModuleRepository,
				courseLessonRepository, courseAccessGuard);
	}

	@Test
	void createLessonRequiresCreateEditAccessAndPersistsWhenSequenceIsFree() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture();
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseLessonRepository.existsByModuleIdAndSequence(module.getId(), 1)).thenReturn(false);
		when(courseLessonRepository.save(any(CourseLesson.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CourseLessonView view = courseLessonService.createLesson(courseId, moduleId, "Lesson 1", 1);

		verify(courseAccessGuard).requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		assertThat(view.title()).isEqualTo("Lesson 1");
		assertThat(view.sequence()).isEqualTo(1);
	}

	@Test
	void createLessonThrowsConflictWhenSequenceAlreadyExistsInThisModule() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture();
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseLessonRepository.existsByModuleIdAndSequence(module.getId(), 1)).thenReturn(true);

		assertThatThrownBy(() -> courseLessonService.createLesson(courseId, moduleId, "Lesson 1", 1))
			.isInstanceOf(ConflictException.class);
		verify(courseLessonRepository, never()).save(any());
	}

	@Test
	void createLessonThrowsNotFoundWhenTheModuleDoesNotBelongToThisCourse() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		Course course = courseFixture();
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> courseLessonService.createLesson(courseId, moduleId, "Lesson 1", 1))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void updateLessonThrowsConflictWhenTheNewSequenceCollidesWithAnotherLesson() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		Course course = courseFixture();
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		CourseLesson lesson = new CourseLesson(TENANT_ID, moduleId, "Lesson 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())).thenReturn(Optional.of(lesson));
		when(courseLessonRepository.existsByModuleIdAndSequenceAndIdNot(module.getId(), 2, lessonId))
			.thenReturn(true);

		assertThatThrownBy(
				() -> courseLessonService.updateLesson(courseId, moduleId, lessonId, "Lesson 1 renamed", 2))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void updateLessonKeepingTheSameSequenceNeverChecksForACollision() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		Course course = courseFixture();
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		CourseLesson lesson = new CourseLesson(TENANT_ID, moduleId, "Lesson 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())).thenReturn(Optional.of(lesson));

		CourseLessonView view = courseLessonService.updateLesson(courseId, moduleId, lessonId, "Lesson 1 renamed", 1);

		assertThat(view.title()).isEqualTo("Lesson 1 renamed");
		verify(courseLessonRepository, never()).existsByModuleIdAndSequenceAndIdNot(any(), any(), any());
	}

	@Test
	void deleteLessonRequiresCreateEditAccessNotDelete() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		Course course = courseFixture();
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		CourseLesson lesson = new CourseLesson(TENANT_ID, moduleId, "Lesson 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())).thenReturn(Optional.of(lesson));

		courseLessonService.deleteLesson(courseId, moduleId, lessonId);

		// Per the API contract table (plan §10), lesson delete is gated on
		// CREATE_EDIT for every eligible caller (staff or owning Teacher) -
		// unlike course-level delete, which is Tenant-Admin-only via DELETE.
		verify(courseAccessGuard).requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		verify(courseLessonRepository).delete(lesson);
	}

	@Test
	void deleteLessonThrowsNotFoundWhenTheLessonDoesNotBelongToThisModule() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		Course course = courseFixture();
		CourseModule module = new CourseModule(TENANT_ID, courseId, "Module 1", 1);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(courseModuleRepository.findByIdAndCourseId(moduleId, courseId)).thenReturn(Optional.of(module));
		when(courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> courseLessonService.deleteLesson(courseId, moduleId, lessonId))
			.isInstanceOf(NotFoundException.class);
	}

	private static Course courseFixture() {
		return new Course(TENANT_ID, UUID.randomUUID(), "Test Course", "test-course", "Math", null, null, null, null,
				null, new BigDecimal("10.00"), null, null, CourseStatus.DRAFT);
	}

}
