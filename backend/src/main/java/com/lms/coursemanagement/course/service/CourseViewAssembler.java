package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseCommissionRate;
import com.lms.coursemanagement.course.domain.CourseLink;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.domain.CourseTag;
import com.lms.coursemanagement.course.domain.CourseTagAssignment;
import com.lms.coursemanagement.course.domain.CourseTeacherAssignment;
import com.lms.coursemanagement.course.repository.CourseCommissionRateRepository;
import com.lms.coursemanagement.course.repository.CourseLinkRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseTagAssignmentRepository;
import com.lms.coursemanagement.course.repository.CourseTagRepository;
import com.lms.coursemanagement.course.repository.CourseTeacherAssignmentRepository;
import com.lms.coursemanagement.course.service.CourseView.LinkedCourseRef;
import com.lms.coursemanagement.course.service.CourseView.PriceDisplay;
import com.lms.coursemanagement.course.service.CourseView.TagRef;
import com.lms.coursemanagement.course.service.CourseView.TeacherRef;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.FacilityFeeSettings;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds {@link CourseView}s for one course or a page of courses with a
 * fixed number of batched, tenant-scoped reads (never one read per course):
 * teacher assignments + names, tags, linked courses, current commission
 * rates and checkout amounts. Cover images become short-lived signed URLs
 * ({@link #COVER_URL_TTL}); a storage failure degrades to {@code null}
 * instead of failing the whole listing.
 *
 * <p>{@code staffView = false} (a Student reading a PUBLIC/enrolled course)
 * omits the commission rate and never falls back to a teacher's account
 * email when the teacher has no profile name.
 */
@Component
class CourseViewAssembler {

	private static final Logger log = LoggerFactory.getLogger(CourseViewAssembler.class);

	static final Duration COVER_URL_TTL = Duration.ofMinutes(15);

	private final CourseCheckoutAmountResolver checkoutAmountResolver;

	private final CourseRepository courseRepository;

	private final CourseTeacherAssignmentRepository teacherAssignmentRepository;

	private final CourseTagAssignmentRepository tagAssignmentRepository;

	private final CourseTagRepository tagRepository;

	private final CourseLinkRepository linkRepository;

	private final CourseCommissionRateRepository commissionRateRepository;

	private final ObjectStorageApi objectStorageApi;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final TenantConfigApi tenantConfigApi;

	CourseViewAssembler(CourseCheckoutAmountResolver checkoutAmountResolver, CourseRepository courseRepository,
			CourseTeacherAssignmentRepository teacherAssignmentRepository,
			CourseTagAssignmentRepository tagAssignmentRepository, CourseTagRepository tagRepository,
			CourseLinkRepository linkRepository, CourseCommissionRateRepository commissionRateRepository,
			ObjectStorageApi objectStorageApi, TeacherLookupApi teacherLookupApi,
			UserProvisioningApi userProvisioningApi, TenantConfigApi tenantConfigApi) {
		this.checkoutAmountResolver = checkoutAmountResolver;
		this.courseRepository = courseRepository;
		this.teacherAssignmentRepository = teacherAssignmentRepository;
		this.tagAssignmentRepository = tagAssignmentRepository;
		this.tagRepository = tagRepository;
		this.linkRepository = linkRepository;
		this.commissionRateRepository = commissionRateRepository;
		this.objectStorageApi = objectStorageApi;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.tenantConfigApi = tenantConfigApi;
	}

	CourseView toView(Course course, boolean staffView) {
		return toViews(List.of(course), staffView).get(0);
	}

	/** Student view for a caller whose participation is decided by {@code studentParticipates}. */
	CourseView toStudentView(Course course, Predicate<UUID> studentParticipates) {
		return toViews(List.of(course), false, studentParticipates).get(0);
	}

	/**
	 * {@code staffView = false} without a participation predicate (e.g. the
	 * catalog) treats the caller as a participant of nothing: no invite links,
	 * PUBLIC linked courses only.
	 */
	List<CourseView> toViews(List<Course> courses, boolean staffView) {
		return toViews(courses, staffView, staffView ? null : courseId -> false);
	}

	/**
	 * KLASS-T8b SEC M1: in the student view ({@code staffView = false}) the
	 * WhatsApp/Telegram invite links are returned only when {@code
	 * studentParticipates} accepts the course, and a linked source course is
	 * listed only when it is PUBLIC or the student participates in it.
	 * Teacher ids/names and the enrollment fee stay (informational).
	 */
	List<CourseView> toViews(List<Course> courses, boolean staffView, Predicate<UUID> studentParticipates) {
		Predicate<UUID> participates = staffView ? courseId -> true
				: studentParticipates != null ? studentParticipates : courseId -> false;
		if (courses.isEmpty()) {
			return List.of();
		}
		Set<UUID> courseIds = courses.stream().map(Course::getId).collect(Collectors.toSet());
		Map<UUID, CheckoutAmount> amounts = checkoutAmountResolver.resolveBatch(courses);
		String timezone = tenantTimezone(courses.get(0).getTenantId());
		FacilityFeeSettings feeSettings = FacilityFeeSettings
			.from(tenantConfigApi.resolveDomain(courses.get(0).getTenantId(), ConfigDomain.PAYMENT));

		Map<UUID, List<CourseTeacherAssignment>> assignmentsByCourse = teacherAssignmentRepository
			.findByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.groupingBy(CourseTeacherAssignment::getCourseId, LinkedHashMap::new,
					Collectors.toList()));
		Set<UUID> teacherIds = new HashSet<>();
		courses.forEach(course -> teacherIds.add(course.getTeacherId()));
		assignmentsByCourse.values().forEach(list -> list.forEach(a -> teacherIds.add(a.getTeacherId())));
		Map<UUID, String> teacherNames = resolveTeacherNames(teacherIds, staffView);

		Map<UUID, List<CourseTagAssignment>> tagAssignmentsByCourse = tagAssignmentRepository
			.findByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.groupingBy(CourseTagAssignment::getCourseId));
		Set<UUID> tagIds = tagAssignmentsByCourse.values()
			.stream()
			.flatMap(List::stream)
			.map(CourseTagAssignment::getTagId)
			.collect(Collectors.toSet());
		Map<UUID, CourseTag> tagsById = tagIds.isEmpty() ? Map.of()
				: tagRepository.findAllById(tagIds)
					.stream()
					.collect(Collectors.toMap(CourseTag::getId, Function.identity()));

		Map<UUID, List<CourseLink>> linksByParent = linkRepository.findByParentCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.groupingBy(CourseLink::getParentCourseId, LinkedHashMap::new, Collectors.toList()));
		Set<UUID> sourceIds = linksByParent.values()
			.stream()
			.flatMap(List::stream)
			.map(CourseLink::getSourceCourseId)
			.collect(Collectors.toSet());
		Map<UUID, Course> sources = sourceIds.isEmpty() ? Map.of()
				: courseRepository.findAllById(sourceIds)
					.stream()
					.collect(Collectors.toMap(Course::getId, Function.identity()));

		Map<UUID, CourseCommissionRate> commissionRates = staffView
				? commissionRateRepository.findCurrentByCourseIdIn(courseIds) : Map.of();

		List<CourseView> views = new ArrayList<>(courses.size());
		for (Course course : courses) {
			List<TeacherRef> teachers = teacherRefs(course,
					assignmentsByCourse.getOrDefault(course.getId(), List.of()), teacherNames);
			List<TagRef> tags = tagAssignmentsByCourse.getOrDefault(course.getId(), List.of())
				.stream()
				.map(assignment -> tagsById.get(assignment.getTagId()))
				.filter(java.util.Objects::nonNull)
				.map(tag -> new TagRef(tag.getId(), tag.getName()))
				.sorted(Comparator.comparing(TagRef::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
			List<LinkedCourseRef> linked = linksByParent.getOrDefault(course.getId(), List.of())
				.stream()
				.map(link -> sources.get(link.getSourceCourseId()))
				.filter(java.util.Objects::nonNull)
				.filter(source -> staffView || source.getStatus() == CourseStatus.PUBLIC
						|| participates.test(source.getId()))
				.map(source -> new LinkedCourseRef(source.getId(), source.getName()))
				.toList();
			boolean showInvites = staffView || participates.test(course.getId());
			CourseCommissionRate rate = commissionRates.get(course.getId());
			CheckoutAmount resolved = amounts.get(course.getId());
			views.add(new CourseView(course.getId(), course.getTeacherId(), course.getName(), course.getSlug(),
					course.getCategory(), course.getSubject(), course.getStream(), course.getGrade(),
					course.getAcademicYear(), course.getDescription(), course.getPrice(),
					course.getAccessDurationDays(), course.getEnrollmentRule(), course.getStatus(),
					course.getPricingModel(), course.getArchivedAt(), course.getCreatedAt(), course.getUpdatedAt(),
					resolved != null ? resolved.amount() : null, resolved != null ? resolved.currency() : null,
					resolved != null && resolved.requiresManualQuote(), course.isRegistrationEnabled(),
					course.getEnrollmentFee(), coverUrl(course), showInvites ? course.getWhatsappInviteUrl() : null,
					showInvites ? course.getTelegramInviteUrl() : null, course.getAccessDurationDays(), rate != null ? rate.getRate() : null,
					teachers, tags, linked, course.getDisplayOrder(), priceDisplay(course, resolved), timezone,
					course.getFacilityFeeOverride(), feeSettings.amount(), feeSettings.mode(),
					feeSettings.effectiveFor(course.getFacilityFeeOverride()), course.getPostageFee()));
		}
		return views;
	}

	/**
	 * Teacher ids (tenant-scoped teacher directory) whose display name
	 * contains {@code search}, case-insensitively - feeds the admin list's
	 * server-side "search by teacher name".
	 */
	Set<UUID> teacherIdsMatchingName(String search) {
		if (search == null || search.isBlank()) {
			return Set.of();
		}
		String needle = search.strip().toLowerCase(Locale.ROOT);
		return teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> teacher.name() != null && teacher.name().toLowerCase(Locale.ROOT).contains(needle))
			.map(TeacherLookupApi.TeacherSummary::userId)
			.collect(Collectors.toSet());
	}

	private List<TeacherRef> teacherRefs(Course course, List<CourseTeacherAssignment> assignments,
			Map<UUID, String> names) {
		if (assignments.isEmpty()) {
			// A course written outside CourseService (e.g. a fixture) with no
			// assignment rows: course.teacher_id is still the primary.
			return List.of(new TeacherRef(course.getTeacherId(), names.get(course.getTeacherId()), true));
		}
		return assignments.stream()
			.map(assignment -> new TeacherRef(assignment.getTeacherId(), names.get(assignment.getTeacherId()),
					assignment.isPrimary()))
			.toList();
	}

	private Map<UUID, String> resolveTeacherNames(Collection<UUID> teacherIds, boolean emailFallback) {
		Map<UUID, String> names = new HashMap<>();
		teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> teacherIds.contains(teacher.userId()) && teacher.name() != null
					&& !teacher.name().isBlank())
			.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		List<UUID> missing = teacherIds.stream().filter(id -> !names.containsKey(id)).toList();
		if (emailFallback && !missing.isEmpty()) {
			for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(missing)) {
				names.putIfAbsent(summary.userId(), summary.email());
			}
		}
		return names;
	}

	private String coverUrl(Course course) {
		if (course.getCoverImageObjectKey() == null) {
			return null;
		}
		try {
			return objectStorageApi.generateSignedDownloadUrl(course.getCoverImageObjectKey(), COVER_URL_TTL).url();
		}
		catch (RuntimeException ex) {
			log.warn("Could not sign the cover image URL of course {}", course.getId(), ex);
			return null;
		}
	}

	private static PriceDisplay priceDisplay(Course course, CheckoutAmount resolved) {
		if (resolved == null || resolved.amount() == null) {
			return null;
		}
		if (course.getPricingModel() == CoursePricingModel.ONE_TIME) {
			return new PriceDisplay(resolved.amount(), resolved.currency(), null);
		}
		if (course.getPricingModel() == CoursePricingModel.MONTHLY) {
			return new PriceDisplay(resolved.amount(), resolved.currency(), "MONTH");
		}
		return null;
	}
	/** The tenant's {@code GENERAL.default_timezone} (IANA id), {@code UTC} when unset/invalid. */
	String tenantTimezone(UUID tenantId) {
		try {
			Object configured = tenantConfigApi.resolveValue(tenantId, ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zoneName) {
				return ZoneId.of(zoneName).getId();
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			log.warn("Invalid tenant default_timezone; using UTC", ex);
		}
		return "UTC";
	}

}
