package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.PermissionAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Course structure (lessons) CRUD - "structure only", per plan §9/§10. Every
 * public method resolves the parent course AND the parent module (verifying
 * the module actually belongs to that course, not merely to the same
 * tenant) before touching a lesson, then re-runs {@link CourseAccessGuard
 * #requireCourseAccess} - same defense-in-depth discipline as {@link
 * CourseModuleService}.
 */
@Service
@Transactional
public class CourseLessonService {

	private final CourseRepository courseRepository;

	private final CourseModuleRepository courseModuleRepository;

	private final CourseLessonRepository courseLessonRepository;

	private final CourseAccessGuard courseAccessGuard;

	public CourseLessonService(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository, CourseAccessGuard courseAccessGuard) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.courseAccessGuard = courseAccessGuard;
	}

	@Transactional(readOnly = true)
	public List<CourseLessonView> listLessons(UUID courseId, UUID moduleId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		CourseModule module = loadModule(courseId, moduleId);
		return courseLessonRepository.findByModuleId(module.getId())
			.stream()
			.map(CourseLessonService::toView)
			.toList();
	}

	public CourseLessonView createLesson(UUID courseId, UUID moduleId, String title, Integer sequence) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);

		if (courseLessonRepository.existsByModuleIdAndSequence(module.getId(), sequence)) {
			throw new ConflictException("A lesson with this sequence already exists in this module");
		}

		CourseLesson lesson = new CourseLesson(course.getTenantId(), module.getId(), title, sequence);
		lesson = courseLessonRepository.save(lesson);
		return toView(lesson);
	}

	public CourseLessonView updateLesson(UUID courseId, UUID moduleId, UUID lessonId, String title,
			Integer sequence) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);

		CourseLesson lesson = courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())
			.orElseThrow(() -> new NotFoundException("Lesson not found"));

		if (!lesson.getSequence().equals(sequence)
				&& courseLessonRepository.existsByModuleIdAndSequenceAndIdNot(module.getId(), sequence, lessonId)) {
			throw new ConflictException("A lesson with this sequence already exists in this module");
		}

		lesson.setTitle(title);
		lesson.setSequence(sequence);
		return toView(lesson);
	}

	public void deleteLesson(UUID courseId, UUID moduleId, UUID lessonId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);

		CourseLesson lesson = courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())
			.orElseThrow(() -> new NotFoundException("Lesson not found"));
		courseLessonRepository.delete(lesson);
	}

	private Course loadCourse(UUID courseId) {
		return courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private CourseModule loadModule(UUID courseId, UUID moduleId) {
		return courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
			.orElseThrow(() -> new NotFoundException("Module not found"));
	}

	private static CourseLessonView toView(CourseLesson lesson) {
		return new CourseLessonView(lesson.getId(), lesson.getModuleId(), lesson.getTitle(), lesson.getSequence(),
				lesson.getCreatedAt(), lesson.getUpdatedAt());
	}

}
