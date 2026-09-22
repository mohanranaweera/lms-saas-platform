package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.common.money.PlatformCurrency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Batched, non-throwing sibling of {@link CourseLookupApiImpl
 * #getResolvedCheckoutAmount(UUID)}'s single-course resolution - used by
 * multi-course read paths ({@code CoursePublicService}'s storefront listing,
 * {@code CourseService}'s internal listing) so they never resolve a course's
 * checkout amount by looping a per-course billing-configuration/billing
 * -period lookup (an N+1 query shape), mirroring {@code CourseLookupApiImpl
 * #getCourseSummaries(Set)}'s existing single-query batching precedent.
 *
 * <p>Deliberately package-private - this is an internal implementation
 * helper of {@code course-management}'s own read paths, never a class other
 * domains are permitted to depend on (that contract stays {@link
 * com.lms.coursemanagement.api.CourseLookupApi} only, per {@code
 * .claude/rules/architecture.md}).
 *
 * <p>Unlike {@link CourseLookupApiImpl#getResolvedCheckoutAmount(UUID)},
 * {@link #resolveBatch(Collection)} never throws {@code
 * CourseBillingNotConfiguredException} for a course whose {@code
 * pricingModel} requires billing configuration that does not (yet) exist -
 * a public/authenticated storefront read must degrade gracefully (a course
 * simply doesn't show a resolved amount yet) rather than 500ing an entire
 * listing page because one course among many is still being configured.
 * Such a course is simply absent from the returned map - callers MUST treat
 * a missing key as "not yet available for checkout", never fall back to a
 * silent {@code $0}.
 */
@Component
class CourseCheckoutAmountResolver {

	private final CourseBillingConfigurationRepository courseBillingConfigurationRepository;

	private final CourseBillingPeriodRepository courseBillingPeriodRepository;

	CourseCheckoutAmountResolver(CourseBillingConfigurationRepository courseBillingConfigurationRepository,
			CourseBillingPeriodRepository courseBillingPeriodRepository) {
		this.courseBillingConfigurationRepository = courseBillingConfigurationRepository;
		this.courseBillingPeriodRepository = courseBillingPeriodRepository;
	}

	Map<UUID, CheckoutAmount> resolveBatch(Collection<Course> courses) {
		Map<UUID, CheckoutAmount> resolved = new HashMap<>();
		List<Course> needsBillingLookup = new ArrayList<>();
		for (Course course : courses) {
			CoursePricingModel pricingModel = course.getPricingModel();
			switch (pricingModel) {
				case FREE -> resolved.put(course.getId(),
						new CheckoutAmount(BigDecimal.ZERO, PlatformCurrency.DEFAULT_CURRENCY, null, false, true));
				case ONE_TIME -> resolved.put(course.getId(), new CheckoutAmount(course.getPrice(),
						PlatformCurrency.DEFAULT_CURRENCY, null, false, false));
				case MONTHLY, SESSION, CUSTOM -> needsBillingLookup.add(course);
			}
		}
		if (needsBillingLookup.isEmpty()) {
			return resolved;
		}

		Set<UUID> courseIds = needsBillingLookup.stream().map(Course::getId).collect(Collectors.toSet());
		Map<UUID, CourseBillingConfiguration> configurationsByCourseId = courseBillingConfigurationRepository
			.findByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.toMap(CourseBillingConfiguration::getCourseId, configuration -> configuration));

		Set<UUID> billingConfigurationIdsNeedingOpenPeriod = needsBillingLookup.stream()
			.filter(course -> course.getPricingModel() == CoursePricingModel.MONTHLY
					|| course.getPricingModel() == CoursePricingModel.SESSION)
			.map(course -> configurationsByCourseId.get(course.getId()))
			.filter(Objects::nonNull)
			.map(CourseBillingConfiguration::getId)
			.collect(Collectors.toSet());

		Map<UUID, CourseBillingPeriod> openPeriodsByBillingConfigurationId = courseBillingPeriodRepository
			.findCurrentOpenByBillingConfigurationIdIn(billingConfigurationIdsNeedingOpenPeriod)
			.stream()
			.collect(Collectors.toMap(CourseBillingPeriod::getBillingConfigurationId, period -> period));

		for (Course course : needsBillingLookup) {
			CourseBillingConfiguration configuration = configurationsByCourseId.get(course.getId());
			if (configuration == null) {
				// Genuinely unconfigured yet - the batch-safe equivalent of
				// CourseBillingNotConfiguredException. Left absent from the
				// map rather than thrown, per this method's own javadoc.
				continue;
			}
			if (course.getPricingModel() == CoursePricingModel.CUSTOM) {
				resolved.put(course.getId(),
						new CheckoutAmount(null, configuration.getCurrency(), null, true, false));
				continue;
			}
			CourseBillingPeriod openPeriod = openPeriodsByBillingConfigurationId.get(configuration.getId());
			if (openPeriod == null) {
				continue; // configured, but no open billing period yet - also "not yet available"
			}
			resolved.put(course.getId(), new CheckoutAmount(openPeriod.getAmount(), openPeriod.getCurrency(),
					openPeriod.getId(), false, false));
		}
		return resolved;
	}

}
