package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link CourseLookupApi} - the only class other domains are
 * permitted to depend on for course reads (per {@code
 * .claude/rules/architecture.md}'s "a module may depend only on another
 * module's {@code api} package" rule). Every read is tenant-scoped through
 * {@link CourseRepository#findById}, exactly like every other read in this
 * module.
 */
@Service
@Transactional(readOnly = true)
public class CourseLookupApiImpl implements CourseLookupApi {

	private final CourseRepository courseRepository;

	private final CourseModuleRepository courseModuleRepository;

	private final CourseLessonRepository courseLessonRepository;

	public CourseLookupApiImpl(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
	}

	@Override
	public boolean isPublished(UUID courseId) {
		return courseRepository.findById(courseId).map(Course::getStatus).filter(status -> status == CourseStatus.PUBLIC).isPresent();
	}

	@Override
	public Optional<UUID> getTeacherId(UUID courseId) {
		return courseRepository.findById(courseId).map(Course::getTeacherId);
	}

	@Override
	public Optional<BigDecimal> getCurrentPrice(UUID courseId) {
		return courseRepository.findById(courseId).map(Course::getPrice);
	}

	@Override
	public Optional<CourseAccessWindow> getAccessDurationDays(UUID courseId) {
		return courseRepository.findById(courseId).map(course -> new CourseAccessWindow(course.getAccessDurationDays()));
	}

	@Override
	public List<CourseSummary> getCourseSummaries(Set<UUID> courseIds) {
		return courseRepository.findAllById(courseIds)
			.stream()
			.map(course -> new CourseSummary(course.getId(), course.getName(), course.getSlug(), course.getCategory()))
			.toList();
	}

	@Override
	public Optional<LessonOwnership> resolveLessonOwnership(UUID lessonId) {
		return courseLessonRepository.findById(lessonId)
			.flatMap(lesson -> courseModuleRepository.findById(lesson.getModuleId())
				.flatMap(module -> courseRepository.findById(module.getCourseId())
					.map(course -> new LessonOwnership(lesson.getId(), module.getId(), course.getId(),
							course.getTeacherId(), course.getStatus() == CourseStatus.PUBLIC))));
	}

}
