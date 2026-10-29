package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.ModulePriceInfo;
import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseBillingNotConfiguredException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CoursePriceOption;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.coursemanagement.api.PublicCourseSummary;
import com.lms.coursemanagement.api.PublishedCourseCard;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseLink;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseLinkRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseTagAssignmentRepository;
import com.lms.coursemanagement.api.CourseTagRef;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
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

	private final CourseBillingConfigurationRepository courseBillingConfigurationRepository;

	private final CourseBillingPeriodRepository courseBillingPeriodRepository;

	private final TenantConfigApi tenantConfigApi;

	private final CourseLinkRepository courseLinkRepository;

	private final CourseTagAssignmentRepository courseTagAssignmentRepository;
	private final ObjectStorageApi objectStorageApi;

	private final CourseCheckoutAmountResolver courseCheckoutAmountResolver;

	public CourseLookupApiImpl(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository,
			CourseBillingConfigurationRepository courseBillingConfigurationRepository,
			CourseBillingPeriodRepository courseBillingPeriodRepository, TenantConfigApi tenantConfigApi,
			CourseLinkRepository courseLinkRepository, CourseTagAssignmentRepository courseTagAssignmentRepository) {
		this(courseRepository, courseModuleRepository, courseLessonRepository, courseBillingConfigurationRepository,
				courseBillingPeriodRepository, tenantConfigApi, courseLinkRepository, courseTagAssignmentRepository, null);
	}

	@Autowired
	public CourseLookupApiImpl(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository,
			CourseBillingConfigurationRepository courseBillingConfigurationRepository,
			CourseBillingPeriodRepository courseBillingPeriodRepository, TenantConfigApi tenantConfigApi,
			CourseLinkRepository courseLinkRepository, CourseTagAssignmentRepository courseTagAssignmentRepository,
			ObjectStorageApi objectStorageApi) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.courseBillingConfigurationRepository = courseBillingConfigurationRepository;
		this.courseBillingPeriodRepository = courseBillingPeriodRepository;
		this.tenantConfigApi = tenantConfigApi;
		this.courseLinkRepository = courseLinkRepository;
		this.courseTagAssignmentRepository = courseTagAssignmentRepository;
		this.objectStorageApi = objectStorageApi;
		this.courseCheckoutAmountResolver = new CourseCheckoutAmountResolver(courseBillingConfigurationRepository,
				courseBillingPeriodRepository, tenantConfigApi);
	}

	@Override
	public Map<UUID, List<CourseTagRef>> findTagsByCourseIds(Set<UUID> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<CourseTagRef>> tags = new LinkedHashMap<>();
		for (Object[] row : courseTagAssignmentRepository.findTagRowsByCourseIds(courseIds)) {
			tags.computeIfAbsent((UUID) row[0], ignored -> new ArrayList<>())
				.add(new CourseTagRef((UUID) row[1], (String) row[2]));
		}
		return tags;
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
	public List<PublishedCourseCard> getPublishedCourseCards(List<UUID> courseIds, java.time.Duration coverUrlTtl) {
		if (courseIds == null || courseIds.isEmpty()) {
			return List.of();
		}
		List<UUID> orderedIds = courseIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
		// Tenant-scoped findAllById (ADR-006): a foreign tenant's id is simply absent.
		Map<UUID, Course> courses = courseRepository.findAllById(orderedIds)
			.stream()
			.filter(course -> course.getStatus() == CourseStatus.PUBLIC)
			.collect(Collectors.toMap(Course::getId, course -> course));
		// Same resolver as the public course catalog, so price/access labels always agree.
		Map<UUID, CheckoutAmount> resolved = courseCheckoutAmountResolver.resolveBatch(courses.values());
		return orderedIds.stream()
			.map(courses::get)
			.filter(java.util.Objects::nonNull)
			.map(course -> {
				CheckoutAmount amount = resolved.get(course.getId());
				return new PublishedCourseCard(course.getId(), course.getName(), course.getSlug(),
						course.getCategory(), course.getPricingModel() == null ? null : course.getPricingModel().name(),
						amount != null ? amount.amount() : null, amount != null ? amount.currency() : null,
						amount != null && amount.requiresManualQuote(), coverUrl(course, coverUrlTtl));
			})
			.toList();
	}

	@Override
	public List<CourseSummary> listCourseSummaries() {
		return courseRepository
			.findAll(org.springframework.data.domain.PageRequest.of(0, MAX_COURSE_SUMMARIES,
					org.springframework.data.domain.Sort.by("name").and(org.springframework.data.domain.Sort.by("id"))))
			.stream()
			.map(course -> new CourseSummary(course.getId(), course.getName(), course.getSlug(), course.getCategory()))
			.sorted(java.util.Comparator.comparing(CourseSummary::name, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(CourseSummary::id))
			.toList();
	}

	@Override
	public Map<UUID, UUID> getTeacherIdsByCourseId(Set<UUID> courseIds) {
		return courseRepository.findAllById(courseIds)
			.stream()
			.collect(Collectors.toMap(Course::getId, Course::getTeacherId));
	}

	@Override
	public List<CourseSummary> listCourseSummariesByTeacherId(UUID teacherId) {
		if (teacherId == null) {
			return List.of();
		}
		return courseRepository.findByTeacherId(teacherId)
			.stream()
			.map(course -> new CourseSummary(course.getId(), course.getName(), course.getSlug(), course.getCategory()))
			.sorted(java.util.Comparator.comparing(CourseSummary::name, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(CourseSummary::id))
			.toList();
	}

	@Override
	public List<com.lms.coursemanagement.api.PublicCourseSummary> listPublicCourseSummariesByTeacherId(UUID teacherId) {
		if (teacherId == null) {
			return List.of();
		}
		return courseRepository.findByTeacherId(teacherId)
			.stream()
			.filter(course -> course.getStatus() == CourseStatus.PUBLIC && !course.isArchived())
			.map(course -> new com.lms.coursemanagement.api.PublicCourseSummary(course.getId(), course.getName(), course.getSlug(), course.getCategory(), coverUrl(course)))
			.sorted(java.util.Comparator.comparing(com.lms.coursemanagement.api.PublicCourseSummary::name, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(com.lms.coursemanagement.api.PublicCourseSummary::id))
			.toList();
	}

	@Override
	public Optional<CheckoutAmount> getResolvedCheckoutAmount(UUID courseId) {
		return courseRepository.findById(courseId).map(this::resolveCheckoutAmount);
	}

	private String coverUrl(Course course) {
		return coverUrl(course, java.time.Duration.ofMinutes(5));
	}

	private String coverUrl(Course course, java.time.Duration ttl) {
		if (objectStorageApi == null || course.getCoverImageObjectKey() == null) return null;
		try {
			return objectStorageApi.generateSignedDownloadUrl(course.getCoverImageObjectKey(), ttl).url();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private CheckoutAmount resolveCheckoutAmount(Course course) {
		CoursePricingModel pricingModel = course.getPricingModel();
		boolean freePricing = pricingModel == CoursePricingModel.FREE;
		return switch (pricingModel) {
			case FREE ->
				new CheckoutAmount(BigDecimal.ZERO, tenantConfigApi.resolveDefaultCurrency(course.getTenantId()), null,
						false, freePricing);
			case ONE_TIME ->
				new CheckoutAmount(course.getPrice(), tenantConfigApi.resolveDefaultCurrency(course.getTenantId()),
						null, false, freePricing);
			case MONTHLY, SESSION -> {
				CourseBillingConfiguration configuration = requireBillingConfiguration(course);
				CourseBillingPeriod period = courseBillingPeriodRepository
					.findCurrentOpenByBillingConfigurationId(configuration.getId())
					.orElseThrow(() -> new CourseBillingNotConfiguredException(
							"No active billing period is configured for this course"));
				yield new CheckoutAmount(period.getAmount(), period.getCurrency(), period.getId(), false,
						freePricing);
			}
			case CUSTOM -> {
				CourseBillingConfiguration configuration = requireBillingConfiguration(course);
				yield new CheckoutAmount(null, configuration.getCurrency(), null, true, freePricing);
			}
			// KLASS-T4a (D09): these models have no whole-course amount. The
			// existing whole-course order flow must refuse them (409), never
			// charge $0; module/lesson/monthly purchases go through the composer.
			case DAILY, MODULE, INSTALLMENT -> throw new CourseBillingNotConfiguredException(
					"This course is not sold as a whole course");
		};
	}

	private CourseBillingConfiguration requireBillingConfiguration(Course course) {
		return courseBillingConfigurationRepository.findByCourseId(course.getId())
			.orElseThrow(() -> new CourseBillingNotConfiguredException("Course billing is not configured for this course"));
	}

	@Override
	public Optional<LessonOwnership> resolveLessonOwnership(UUID lessonId) {
		return courseLessonRepository.findById(lessonId)
			.flatMap(lesson -> courseModuleRepository.findById(lesson.getModuleId())
				.flatMap(module -> courseRepository.findById(module.getCourseId())
					.map(course -> new LessonOwnership(lesson.getId(), module.getId(), course.getId(),
							course.getTeacherId(), course.getStatus() != CourseStatus.DRAFT))));
	}

	@Override
	public Set<UUID> findLessonIdsByTeacherId(UUID teacherUserId) {
		Set<UUID> courseIds = courseRepository.findByTeacherId(teacherUserId).stream()
			.map(Course::getId)
			.collect(Collectors.toSet());
		Set<UUID> moduleIds = courseModuleRepository.findByCourseIds(courseIds).stream()
			.map(com.lms.coursemanagement.course.domain.CourseModule::getId)
			.collect(Collectors.toSet());
		return courseLessonRepository.findByModuleIds(moduleIds).stream()
			.map(com.lms.coursemanagement.course.domain.CourseLesson::getId)
			.collect(Collectors.toSet());
	}

	@Override
	public List<LessonPolicy> findLessonPolicies(UUID courseId) {
		if (courseRepository.findById(courseId).isEmpty()) {
			return List.of();
		}
		List<CourseModule> modules = courseModuleRepository.findByCourseId(courseId);
		if (modules.isEmpty()) {
			return List.of();
		}
		Map<UUID, CourseModule> modulesById = new LinkedHashMap<>();
		modules.forEach(module -> modulesById.put(module.getId(), module));
		Map<UUID, List<CourseLesson>> lessonsByModule = courseLessonRepository
			.findByModuleIdsOrdered(modulesById.keySet())
			.stream()
			.collect(Collectors.groupingBy(CourseLesson::getModuleId, LinkedHashMap::new, Collectors.toList()));
		List<LessonPolicy> policies = new ArrayList<>();
		for (CourseModule module : modules) {
			for (CourseLesson lesson : lessonsByModule.getOrDefault(module.getId(), List.of())) {
				policies.add(new LessonPolicy(lesson.getId(), module.getId(), module.getTitle(), module.getSequence(),
						lesson.getTitle(), lesson.getLessonLabel(), lesson.getSequence(), lesson.getResourceType(),
						lesson.getPaymentType(), lesson.getPrice(), lesson.getStartsAt(), lesson.getEndsAt(),
						lesson.getBillingYear(), lesson.getBillingMonth()));
			}
		}
		return List.copyOf(policies);
	}

	@Override
	public Optional<CourseCatalogInfo> findCatalogInfo(UUID courseId) {
		return courseRepository.findById(courseId).map(course -> {
			BigDecimal monthlyAmount = null;
			String monthlyCurrency = null;
			UUID periodId = null;
			if (course.getPricingModel() == CoursePricingModel.MONTHLY) {
				Optional<CourseBillingPeriod> open = courseBillingConfigurationRepository.findByCourseId(courseId)
					.flatMap(configuration -> courseBillingPeriodRepository
						.findCurrentOpenByBillingConfigurationId(configuration.getId()));
				if (open.isPresent()) {
					monthlyAmount = open.get().getAmount();
					monthlyCurrency = open.get().getCurrency();
					periodId = open.get().getId();
				}
			}
			return new CourseCatalogInfo(course.getId(), course.getName(), course.getTeacherId(),
					course.getPricingModel().name(), course.getStatus().name(), course.isArchived(),
					course.isRegistrationEnabled(), course.getAccessDurationDays(), monthlyAmount, monthlyCurrency,
					periodId, tenantConfigApi.resolveDefaultCurrency(course.getTenantId()),
					course.getFacilityFeeOverride(), course.getPostageFee());
		});
	}

	@Override
	public List<CoursePriceOption> findCoursePriceOptions() {
		String tenantCurrency = null;
		List<CoursePriceOption> options = new ArrayList<>();
		for (Course course : courseRepository.findAll()) {
			BigDecimal price = null;
			String currency = null;
			if (course.getPricingModel() == CoursePricingModel.MONTHLY) {
				Optional<CourseBillingPeriod> open = courseBillingConfigurationRepository.findByCourseId(course.getId())
					.flatMap(configuration -> courseBillingPeriodRepository
						.findCurrentOpenByBillingConfigurationId(configuration.getId()));
				if (open.isPresent()) {
					price = open.get().getAmount();
					currency = open.get().getCurrency();
				}
			}
			else if (course.getPricingModel() == CoursePricingModel.ONE_TIME && course.getPrice() != null) {
				if (tenantCurrency == null) {
					tenantCurrency = tenantConfigApi.resolveDefaultCurrency(course.getTenantId());
				}
				price = course.getPrice();
				currency = tenantCurrency;
			}
			options.add(new CoursePriceOption(course.getId(), course.getName(), course.getPricingModel().name(),
					course.getStatus().name(), course.isArchived(), price, currency));
		}
		options.sort(java.util.Comparator.comparing(CoursePriceOption::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(CoursePriceOption::courseId));
		return List.copyOf(options);
	}

	@Override
	public List<UUID> findLinkedSourceCourseIds(UUID parentCourseId) {
		if (courseRepository.findById(parentCourseId).isEmpty()) {
			return List.of();
		}
		return courseLinkRepository.findByParentCourseId(parentCourseId)
			.stream()
			.map(CourseLink::getSourceCourseId)
			.toList();
	}

	@Override
	public List<UUID> findLinkedParentCourseIds(UUID sourceCourseId) {
		return courseLinkRepository
			.findAll((root, query, cb) -> cb.equal(root.get("sourceCourseId"), sourceCourseId),
					CourseLinkRepository.CREATION_ORDER)
			.stream()
			.map(CourseLink::getParentCourseId)
			.toList();
	}

	@Override
	public List<ModulePriceInfo> findModulePrices(UUID courseId) {
		if (courseRepository.findById(courseId).isEmpty()) {
			return List.of();
		}
		return courseModuleRepository.findByCourseId(courseId)
			.stream()
			.map(module -> new ModulePriceInfo(module.getId(), module.getCourseId(), module.getTitle(),
					module.getSequence(), module.getPrice()))
			.toList();
	}

	@Override
	public Map<UUID, YearMonth> findLessonBillingMonths(Set<UUID> lessonIds) {
		Map<UUID, YearMonth> result = new HashMap<>();
		List<UUID> ids = List.copyOf(lessonIds);
		for (int from = 0; from < ids.size(); from += 1000) {
			for (CourseLesson lesson : courseLessonRepository.findAllByIdIn(ids.subList(from,
					Math.min(ids.size(), from + 1000)))) {
				if (lesson.getBillingYear() != null && lesson.getBillingMonth() != null) {
					result.put(lesson.getId(), YearMonth.of(lesson.getBillingYear(), lesson.getBillingMonth()));
				}
			}
		}
		return result;
	}

	@Override
	public Set<UUID> findLessonIdsInBillingMonths(Set<YearMonth> months) {
		Set<UUID> result = new HashSet<>();
		courseLessonRepository.findByBillingMonths(months).forEach(lesson -> result.add(lesson.getId()));
		return result;
	}

}
