package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseRepository;
import java.math.BigDecimal;
import java.util.Optional;
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

	public CourseLookupApiImpl(CourseRepository courseRepository) {
		this.courseRepository = courseRepository;
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

}
