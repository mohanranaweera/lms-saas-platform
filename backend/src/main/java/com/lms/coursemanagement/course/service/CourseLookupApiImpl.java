package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseBillingNotConfiguredException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.common.money.PlatformCurrency;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	public CourseLookupApiImpl(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository,
			CourseBillingConfigurationRepository courseBillingConfigurationRepository,
			CourseBillingPeriodRepository courseBillingPeriodRepository) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.courseBillingConfigurationRepository = courseBillingConfigurationRepository;
		this.courseBillingPeriodRepository = courseBillingPeriodRepository;
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
	public Map<UUID, UUID> getTeacherIdsByCourseId(Set<UUID> courseIds) {
		return courseRepository.findAllById(courseIds)
			.stream()
			.collect(Collectors.toMap(Course::getId, Course::getTeacherId));
	}

	@Override
	public Optional<CheckoutAmount> getResolvedCheckoutAmount(UUID courseId) {
		return courseRepository.findById(courseId).map(this::resolveCheckoutAmount);
	}

	private CheckoutAmount resolveCheckoutAmount(Course course) {
		CoursePricingModel pricingModel = course.getPricingModel();
		boolean freePricing = pricingModel == CoursePricingModel.FREE;
		return switch (pricingModel) {
			case FREE ->
				new CheckoutAmount(BigDecimal.ZERO, PlatformCurrency.DEFAULT_CURRENCY, null, false, freePricing);
			case ONE_TIME ->
				new CheckoutAmount(course.getPrice(), PlatformCurrency.DEFAULT_CURRENCY, null, false, freePricing);
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
							course.getTeacherId(), course.getStatus() == CourseStatus.PUBLIC))));
	}

}
