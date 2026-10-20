package com.lms.coursemanagement.course.service;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.api.CourseCatalogApi;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.domain.CourseTagAssignment;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseSpecifications;
import com.lms.coursemanagement.course.repository.CourseTagAssignmentRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link CourseCatalogApi} (KLASS-T4b, plan §4.2). Every read runs
 * through {@link CourseRepository}'s structurally tenant-scoped {@code
 * findAll(Specification, Pageable)}; the result rows are assembled by the same
 * batched {@link CourseViewAssembler} the admin list uses, in its student mode
 * ({@code staffView = false}: no commission, no email fallback for teacher
 * names).
 */
@Service
@Transactional(readOnly = true)
public class CourseCatalogService implements CourseCatalogApi {

	static final int MAX_PAGE_SIZE = 100;

	private final CourseRepository courseRepository;

	private final CourseTagAssignmentRepository tagAssignmentRepository;

	private final CourseViewAssembler viewAssembler;

	CourseCatalogService(CourseRepository courseRepository, CourseTagAssignmentRepository tagAssignmentRepository,
			CourseViewAssembler viewAssembler) {
		this.courseRepository = courseRepository;
		this.tagAssignmentRepository = tagAssignmentRepository;
		this.viewAssembler = viewAssembler;
	}

	@Override
	public PageResponse<CatalogEntry> findCatalog(CatalogQuery query) {
		int size = Math.max(1, Math.min(query.size(), MAX_PAGE_SIZE));
		PageRequest pageable = PageRequest.of(Math.max(0, query.page()), size, sortFor(query.sort()));
		if (query.restrictToCourseIds() != null && query.restrictToCourseIds().isEmpty()) {
			return PageResponse.from(Page.empty(pageable));
		}

		Specification<Course> spec = (root, q, cb) -> cb.notEqual(root.get("status"), CourseStatus.DRAFT);
		if (query.availableOnly()) {
			spec = spec.and(CourseSpecifications.withStatus(CourseStatus.PUBLIC))
				.and(CourseSpecifications.archived(false))
				.and((root, q, cb) -> cb.isTrue(root.get("registrationEnabled")));
		}
		if (query.restrictToCourseIds() != null) {
			Set<UUID> restrict = query.restrictToCourseIds();
			spec = spec.and((root, q, cb) -> root.get("id").in(restrict));
		}
		if (query.excludeCourseIds() != null && !query.excludeCourseIds().isEmpty()) {
			Set<UUID> exclude = query.excludeCourseIds();
			spec = spec.and((root, q, cb) -> cb.not(root.get("id").in(exclude)));
		}
		if (query.tagId() != null) {
			// Tenant-scoped read: a tag of another tenant has no assignment here.
			Set<UUID> taggedCourseIds = tagAssignmentRepository
				.findAll((root, q, cb) -> cb.equal(root.get("id").get("tagId"), query.tagId()))
				.stream()
				.map(CourseTagAssignment::getCourseId)
				.collect(Collectors.toSet());
			if (taggedCourseIds.isEmpty()) {
				return PageResponse.from(Page.empty(pageable));
			}
			spec = spec.and((root, q, cb) -> root.get("id").in(taggedCourseIds));
		}
		if (query.search() != null && !query.search().isBlank()) {
			spec = spec.and(CourseSpecifications.search(query.search(),
					viewAssembler.teacherIdsMatchingName(query.search())));
		}

		Page<Course> page = courseRepository.findAll(spec, pageable);
		Map<UUID, CourseView> views = viewAssembler.toViews(page.getContent(), false)
			.stream()
			.collect(Collectors.toMap(CourseView::id, Function.identity()));
		return PageResponse.from(page.map(course -> toEntry(views.get(course.getId()))));
	}

	static Sort sortFor(CatalogSort sort) {
		if (sort == CatalogSort.TITLE) {
			return Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));
		}
		return Sort.by(Sort.Order.asc("displayOrder").nullsLast(), Sort.Order.asc("name").ignoreCase(),
				Sort.Order.asc("id"));
	}

	private static CatalogEntry toEntry(CourseView view) {
		// The name may be null (a teacher without a profile name; students never
		// get the account-email fallback).
		CourseView.TeacherRef primary = view.teachers()
			.stream()
			.filter(CourseView.TeacherRef::primary)
			.findFirst()
			.orElse(view.teachers().isEmpty() ? null : view.teachers().get(0));
		String teacherName = primary == null ? null : primary.name();
		List<CatalogTag> tags = view.tags().stream().map(tag -> new CatalogTag(tag.id(), tag.name())).toList();
		CourseView.PriceDisplay price = view.priceDisplay();
		return new CatalogEntry(view.id(), view.name(), teacherName, view.coverImageUrl(),
				price != null ? price.amount() : null, price != null ? price.currency() : null,
				price != null ? price.period() : null, view.pricingModel().name(), tags, view.registrationEnabled());
	}

}
