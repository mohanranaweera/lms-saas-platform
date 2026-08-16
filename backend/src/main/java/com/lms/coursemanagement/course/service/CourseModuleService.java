package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.PermissionAction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Course structure (modules) CRUD - "structure only", per plan §9/§10. Every
 * public method loads the parent course through the same tenant-scoped
 * {@link CourseRepository#findById} used by {@code CourseService}, then
 * re-runs {@link CourseAccessGuard#requireCourseAccess} before touching
 * anything - independent re-verification per method, matching {@code
 * StaffService}'s defense-in-depth discipline. All module/lesson mutations
 * (create/edit/delete) are gated on {@code CREATE_EDIT}, not {@code DELETE}
 * - the API contract table names "Staff CREATE_EDIT, or Teacher if owner"
 * for every one of these rows, including delete, unlike course-level delete
 * which is Tenant-Admin-only.
 */
@Service
@Transactional
public class CourseModuleService {

	private final CourseRepository courseRepository;

	private final CourseModuleRepository courseModuleRepository;

	private final CourseAccessGuard courseAccessGuard;

	public CourseModuleService(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseAccessGuard courseAccessGuard) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseAccessGuard = courseAccessGuard;
	}

	@Transactional(readOnly = true)
	public List<CourseModuleView> listModules(UUID courseId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		return courseModuleRepository.findByCourseId(courseId).stream().map(CourseModuleService::toView).toList();
	}

	public CourseModuleView createModule(UUID courseId, String title, Integer sequence) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);

		if (courseModuleRepository.existsByCourseIdAndSequence(courseId, sequence)) {
			throw new ConflictException("A module with this sequence already exists in this course");
		}

		CourseModule module = new CourseModule(course.getTenantId(), courseId, title, sequence);
		module = courseModuleRepository.save(module);
		return toView(module);
	}

	public CourseModuleView updateModule(UUID courseId, UUID moduleId, String title, Integer sequence) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);

		CourseModule module = courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
			.orElseThrow(() -> new NotFoundException("Module not found"));

		if (!module.getSequence().equals(sequence)
				&& courseModuleRepository.existsByCourseIdAndSequenceAndIdNot(courseId, sequence, moduleId)) {
			throw new ConflictException("A module with this sequence already exists in this course");
		}

		module.setTitle(title);
		module.setSequence(sequence);
		return toView(module);
	}

	public void deleteModule(UUID courseId, UUID moduleId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);

		CourseModule module = courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
			.orElseThrow(() -> new NotFoundException("Module not found"));

		// fk_course_lesson_module declares ON DELETE CASCADE (V14) - the
		// database removes this module's course_lesson rows itself; no
		// manual lesson delete needed here.
		courseModuleRepository.delete(module);
	}

	private Course loadCourse(UUID courseId) {
		return courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private static CourseModuleView toView(CourseModule module) {
		return new CourseModuleView(module.getId(), module.getCourseId(), module.getTitle(), module.getSequence(),
				module.getCreatedAt(), module.getUpdatedAt());
	}

}
