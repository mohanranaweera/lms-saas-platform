package com.lms.coursemanagement.course.service;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.ModulePriceInfo;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.service.CoursePaymentReadinessView.Issue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells course staff whether students can actually pay for a course, by
 * re-applying the student checkout composer's offer rules
 * ({@code CheckoutComposerService#monthlyOffers/moduleOffers/lessonOffers/courseOffers})
 * to the course's own configuration, reading through the same
 * {@link CourseLookupApi} methods the composer uses. Keep the two in step:
 *
 * <ul>
 * <li>MONTHLY - one offer per distinct (billingYear, billingMonth) of the
 * COVERED_BY_CLASS_FEES sessions, only when the open billing period's amount
 * is {@code > 0}.</li>
 * <li>MODULE (any pricing model) - one offer per module with price {@code > 0}.</li>
 * <li>LESSON (any pricing model) - one offer per PAID session with price {@code > 0}.</li>
 * <li>COURSE - ONE_TIME courses whose resolved checkout amount is {@code > 0}.</li>
 * <li>SESSION (legacy) - sold through the whole-course order flow at the open
 * billing period's amount.</li>
 * </ul>
 * FREE and CUSTOM (manual quote) courses never need an offer and never warn.
 * Read-only: no state change, no audit entry.
 */
@Service
@Transactional(readOnly = true)
public class CoursePaymentReadinessService {

	private final CourseRepository courseRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final CourseLookupApi courseLookupApi;

	private final CourseBillingConfigurationRepository billingConfigurationRepository;

	private final CourseBillingPeriodRepository billingPeriodRepository;

	public CoursePaymentReadinessService(CourseRepository courseRepository, CourseAccessGuard courseAccessGuard,
			CourseLookupApi courseLookupApi, CourseBillingConfigurationRepository billingConfigurationRepository,
			CourseBillingPeriodRepository billingPeriodRepository) {
		this.courseRepository = courseRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.courseLookupApi = courseLookupApi;
		this.billingConfigurationRepository = billingConfigurationRepository;
		this.billingPeriodRepository = billingPeriodRepository;
	}

	public CoursePaymentReadinessView getReadiness(UUID courseId) {
		// Tenant-scoped (ADR-006): another tenant's course id is simply absent -> 404.
		Course course = courseRepository.findById(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		courseAccessGuard.requireCourseManagementView(course);
		CoursePricingModel model = course.getPricingModel();
		if (model == CoursePricingModel.FREE || model == CoursePricingModel.CUSTOM) {
			return new CoursePaymentReadinessView(model.name(), true, List.of());
		}
		List<LessonPolicy> lessons = courseLookupApi.findLessonPolicies(courseId);
		List<ModulePriceInfo> modules = courseLookupApi.findModulePrices(courseId);
		boolean hasModuleOffer = modules.stream().anyMatch(module -> positive(module.price()));
		boolean hasLessonOffer = lessons.stream()
			.anyMatch(lesson -> paymentType(lesson) == LessonPaymentType.PAID && positive(lesson.price()));
		List<Issue> issues = new ArrayList<>();
		boolean primaryOffer = switch (model) {
			case MONTHLY -> monthly(courseId, lessons, issues);
			case ONE_TIME -> {
				// Same value the composer's courseOffers reads (resolved ONE_TIME amount = course.price).
				boolean priced = positive(courseLookupApi.getResolvedCheckoutAmount(courseId)
					.map(CheckoutAmount::amount)
					.orElse(null));
				if (!priced) {
					issues.add(new Issue("NO_COURSE_PRICE",
							"The full-course price isn't set. Set a course price above zero so students can pay for the course.",
							null));
				}
				yield priced;
			}
			case SESSION -> {
				// The legacy whole-course order flow charges the open billing period's amount.
				boolean priced = positive(openPeriodAmount(courseId));
				if (!priced) {
					issues.add(new Issue("SESSION_NO_AMOUNT",
							"No per-session amount is set. Add a billing period with an amount above zero.", null));
				}
				yield priced;
			}
			case MODULE -> {
				// A Paid session's own LESSON offer is enough to make a module-priced course payable.
				if (!hasModuleOffer && !hasLessonOffer) {
					issues.add(new Issue("MODULE_NO_PRICED_MODULE",
							"No module has a price. Give at least one module a price above zero (or mark a session as Paid with its own price).",
							null));
				}
				yield hasModuleOffer;
			}
			default -> false; // DAILY, INSTALLMENT: no offer engine of their own yet (D09).
		};
		boolean payable = primaryOffer || hasModuleOffer || hasLessonOffer;
		if (!payable && (model == CoursePricingModel.DAILY || model == CoursePricingModel.INSTALLMENT)) {
			issues.add(new Issue("NO_CHECKOUT_FOR_PRICING_MODEL",
					"Online checkout can't sell this pricing model yet. Give a module a price, mark a session as Paid with its own price, or choose another pricing model.",
					null));
		}
		return new CoursePaymentReadinessView(model.name(), payable, List.copyOf(issues));
	}

	private boolean monthly(UUID courseId, List<LessonPolicy> lessons, List<Issue> issues) {
		BigDecimal amount = courseLookupApi.findCatalogInfo(courseId).map(CourseCatalogInfo::monthlyAmount).orElse(null);
		boolean priced = positive(amount);
		if (!priced) {
			issues.add(new Issue("MONTHLY_NO_AMOUNT",
					"No monthly fee is set. Set a monthly fee above zero so students have an amount to pay.", null));
		}
		TreeSet<Integer> months = new TreeSet<>();
		int withoutMonth = 0;
		for (LessonPolicy lesson : lessons) {
			if (paymentType(lesson) != LessonPaymentType.COVERED_BY_CLASS_FEES) {
				continue;
			}
			if (lesson.billingYear() != null && lesson.billingMonth() != null) {
				months.add(lesson.billingYear() * 100 + lesson.billingMonth());
			}
			else if (lesson.startsAt() != null) {
				withoutMonth++;
			}
		}
		if (withoutMonth > 0) {
			issues.add(new Issue("MONTHLY_SESSIONS_WITHOUT_MONTH", withoutMonth + (withoutMonth == 1
					? " session covered by class fees has a start date but no billing month, so students can't pay for it. It was probably saved before the course was switched to Monthly - open it and save it again."
					: " sessions covered by class fees have a start date but no billing month, so students can't pay for them. They were probably saved before the course was switched to Monthly - open each one and save it again."),
					withoutMonth));
		}
		else if (months.isEmpty()) {
			issues.add(new Issue("MONTHLY_NO_BILLABLE_SESSIONS",
					"No session covered by class fees has a start date, so there is no month for students to pay for. Add dated sessions covered by class fees.",
					null));
		}
		return priced && !months.isEmpty();
	}

	/**
	 * Read directly rather than through {@code getResolvedCheckoutAmount}, which
	 * throws (and so marks this read-only transaction rollback-only) when billing
	 * is not configured.
	 */
	private BigDecimal openPeriodAmount(UUID courseId) {
		return billingConfigurationRepository.findByCourseId(courseId)
			.flatMap(configuration -> billingPeriodRepository
				.findCurrentOpenByBillingConfigurationId(configuration.getId()))
			.map(CourseBillingPeriod::getAmount)
			.orElse(null);
	}

	private static LessonPaymentType paymentType(LessonPolicy lesson) {
		return lesson.paymentType() == null ? LessonPaymentType.COVERED_BY_CLASS_FEES : lesson.paymentType();
	}

	private static boolean positive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

}
