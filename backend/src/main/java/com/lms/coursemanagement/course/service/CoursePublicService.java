package com.lms.coursemanagement.course.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous public storefront read path (plan §9/§15(d)). Deliberately
 * separate from {@link CourseService} and never calls {@code
 * AuthenticatedPrincipalHolder} - there is no authenticated principal for an
 * anonymous storefront request, and this class must not assume one exists.
 * Tenant identity comes from whatever {@link com.lms.common.tenant.TenantContext}
 * value {@code TenantResolutionFilter} already resolved from the request's
 * subdomain (plan's confirmed finding) - never re-derived here, and never a
 * client-supplied {@code tenantId}. {@code status = PUBLIC} is filtered at
 * the repository-query level (never a post-fetch filter), so a DRAFT/PRIVATE
 * course in the same tenant 404s exactly like a nonexistent slug.
 */
@Service
@Transactional(readOnly = true)
public class CoursePublicService {

	/**
	 * Same defensive cap as {@code CourseService#MAX_PAGE_SIZE} - a client
	 * requesting {@code ?size=999999} on the anonymous storefront listing
	 * must never be able to force an unbounded read.
	 */
	private static final int MAX_PAGE_SIZE = 100;

	private final CourseRepository courseRepository;

	public CoursePublicService(CourseRepository courseRepository) {
		this.courseRepository = courseRepository;
	}

	public PageResponse<PublicCourseView> listPublishedCourses(Pageable pageable) {
		Pageable safePageable = clampPageSize(pageable);
		Page<Course> page = courseRepository.findAll(CourseSpecifications.withStatus(CourseStatus.PUBLIC),
				safePageable);
		return PageResponse.from(page.map(CoursePublicService::toView));
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	public PublicCourseView getPublishedCourseBySlug(String slug) {
		Course course = courseRepository.findBySlugAndStatus(slug, CourseStatus.PUBLIC)
			// Generic "not found" - never distinguishes a nonexistent slug
			// from a real DRAFT/PRIVATE slug in the same tenant, per plan
			// §13/§15(d).
			.orElseThrow(() -> new NotFoundException("Course not found"));
		return toView(course);
	}

	private static PublicCourseView toView(Course course) {
		return new PublicCourseView(course.getId(), course.getName(), course.getSlug(), course.getCategory(),
				course.getSubject(), course.getStream(), course.getGrade(), course.getAcademicYear(),
				course.getDescription(), course.getPrice(), course.getAccessDurationDays(),
				course.getEnrollmentRule());
	}

}
