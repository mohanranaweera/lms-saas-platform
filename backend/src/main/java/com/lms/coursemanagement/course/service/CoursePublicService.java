package com.lms.coursemanagement.course.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseSpecifications;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseTagAssignmentRepository;
import com.lms.coursemanagement.course.repository.CourseTagRepository;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

	private final CourseCheckoutAmountResolver courseCheckoutAmountResolver;
	private final TeacherLookupApi teacherLookupApi;
	private final ObjectStorageApi objectStorageApi;
	private final CourseModuleRepository courseModuleRepository;
	private final CourseLessonRepository courseLessonRepository;
	private final CourseTagAssignmentRepository courseTagAssignmentRepository;
	private final CourseTagRepository courseTagRepository;

	@org.springframework.beans.factory.annotation.Autowired
	public CoursePublicService(CourseRepository courseRepository,
			CourseCheckoutAmountResolver courseCheckoutAmountResolver, TeacherLookupApi teacherLookupApi,
			ObjectStorageApi objectStorageApi, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository, CourseTagAssignmentRepository courseTagAssignmentRepository,
			CourseTagRepository courseTagRepository) {
		this.courseRepository = courseRepository;
		this.courseCheckoutAmountResolver = courseCheckoutAmountResolver;
		this.teacherLookupApi = teacherLookupApi;
		this.objectStorageApi = objectStorageApi;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.courseTagAssignmentRepository = courseTagAssignmentRepository;
		this.courseTagRepository = courseTagRepository;
	}

	public CoursePublicService(CourseRepository courseRepository, CourseCheckoutAmountResolver courseCheckoutAmountResolver) {
		this(courseRepository, courseCheckoutAmountResolver, null, null, null, null, null, null);
	}

	/**
	 * Resolves every listed course's checkout amount via a single batched
	 * {@link CourseCheckoutAmountResolver#resolveBatch(java.util.Collection)}
	 * call - never one billing-configuration/billing-period lookup per course
	 * in a loop, since this is a public, unauthenticated, potentially
	 * high-traffic endpoint (Wave 2 gap fix).
	 */
	public List<PublicCourseTag> listPublicTags() {
		if (courseTagAssignmentRepository == null || courseTagRepository == null) return List.of();
		var publishedIds = courseRepository.findAll(CourseSpecifications.withStatus(CourseStatus.PUBLIC)).stream().map(Course::getId).toList();
		if (publishedIds.isEmpty()) return List.of();
		var tagIds = courseTagAssignmentRepository.findByCourseIdIn(publishedIds).stream().map(a -> a.getId().getTagId()).collect(java.util.stream.Collectors.toSet());
		return courseTagRepository.findAllById(tagIds).stream().map(tag -> new PublicCourseTag(tag.getId(), tag.getName())).sorted(java.util.Comparator.comparing(PublicCourseTag::name, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	public PageResponse<PublicCourseView> listPublishedCourses(Pageable pageable) {
		return listPublishedCourses(pageable, null);
	}

	public PageResponse<PublicCourseView> listPublishedCourses(Pageable pageable, UUID tagId) {
		Pageable safePageable = clampPageSize(pageable);
		var spec = CourseSpecifications.withStatus(CourseStatus.PUBLIC);
		if (tagId != null) {
			spec = spec.and((root, query, cb) -> {
				var subquery = query.subquery(UUID.class);
				var assignment = subquery.from(com.lms.coursemanagement.course.domain.CourseTagAssignment.class);
				subquery.select(assignment.get("id").get("courseId"));
				subquery.where(cb.equal(assignment.get("id").get("tagId"), tagId));
				return root.get("id").in(subquery);
			});
		}
		Page<Course> page = courseRepository.findAll(spec, safePageable);
		Map<UUID, CheckoutAmount> resolvedAmounts = courseCheckoutAmountResolver.resolveBatch(page.getContent());
		return PageResponse.from(page.map(course -> toView(course, resolvedAmounts)));
	}

	private PublicCourseOutline buildOutline(Course course) {
		List<PublicCourseOutline.Module> modules = courseModuleRepository.findByCourseId(course.getId()).stream().map(module ->
			new PublicCourseOutline.Module(module.getId(), module.getTitle(), module.getSequence(), courseLessonRepository.findByModuleId(module.getId()).stream()
				.map(lesson -> new PublicCourseOutline.Lesson(lesson.getId(), lesson.getTitle(), lesson.getLessonLabel(), lesson.getResourceType() == null ? null : lesson.getResourceType().name(), lesson.getSequence())).toList())).toList();
		return new PublicCourseOutline(modules);
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
		Map<UUID, CheckoutAmount> resolvedAmounts = courseCheckoutAmountResolver.resolveBatch(List.of(course));
		return toView(course, resolvedAmounts);
	}

	private PublicCourseView toView(Course course, Map<UUID, CheckoutAmount> resolvedAmounts) {
		CheckoutAmount resolved = resolvedAmounts.get(course.getId());
		String teacherName = teacherLookupApi == null ? null : teacherLookupApi.listApprovedActiveTeachers().stream()
			.filter(teacher -> teacher.userId().equals(course.getTeacherId())).map(TeacherLookupApi.TeacherSummary::name).findFirst().orElse(null);
		String coverImageUrl = null;
		if (objectStorageApi != null && course.getCoverImageObjectKey() != null) {
			try { coverImageUrl = objectStorageApi.generateSignedDownloadUrl(course.getCoverImageObjectKey(), java.time.Duration.ofMinutes(15)).url(); }
			catch (RuntimeException ignored) { }
		}
		PublicCourseOutline outline = courseModuleRepository == null || courseLessonRepository == null ? new PublicCourseOutline(List.of()) : buildOutline(course);
		List<PublicCourseTag> tags = courseTagAssignmentRepository == null || courseTagRepository == null ? List.of() : courseTagAssignmentRepository.findByCourseId(course.getId()).stream().map(assignment -> courseTagRepository.findById(assignment.getId().getTagId()).orElse(null)).filter(java.util.Objects::nonNull).map(tag -> new PublicCourseTag(tag.getId(), tag.getName())).toList();
		return new PublicCourseView(course.getId(), course.getName(), course.getSlug(), course.getCategory(),
				course.getSubject(), course.getStream(), course.getGrade(), course.getAcademicYear(),
				course.getDescription(), course.getPrice(), course.getAccessDurationDays(),
				course.getEnrollmentRule(), course.getPricingModel(), resolved != null ? resolved.amount() : null,
				resolved != null ? resolved.currency() : null, resolved != null && resolved.requiresManualQuote(),
				teacherName, coverImageUrl, course.isRegistrationEnabled(), outline, tags);
	}

}
