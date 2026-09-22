package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lms.common.money.PlatformCurrency;
import com.lms.common.persistence.BaseEntity;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseBillingNotConfiguredException;
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
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link CourseLookupApiImpl
 * #getResolvedCheckoutAmount(UUID)} (Wave 2) - proves the per-pricing-model
 * resolution rules documented on {@link CheckoutAmount}, including the
 * "misconfiguration, never a $0 checkout" guarantee for {@code MONTHLY}/
 * {@code SESSION} with no open period and {@code CUSTOM} with no
 * configuration at all.
 */
@ExtendWith(MockitoExtension.class)
class CourseLookupApiResolvedCheckoutAmountTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private CourseModuleRepository courseModuleRepository;

	@Mock
	private CourseLessonRepository courseLessonRepository;

	@Mock
	private CourseBillingConfigurationRepository billingConfigurationRepository;

	@Mock
	private CourseBillingPeriodRepository billingPeriodRepository;

	private CourseLookupApiImpl impl() {
		return new CourseLookupApiImpl(courseRepository, courseModuleRepository, courseLessonRepository,
				billingConfigurationRepository, billingPeriodRepository);
	}

	@Test
	void freeResolvesToZeroWithThePlatformDefaultCurrencyAndNoBillingPeriod() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.FREE, new BigDecimal("0.00"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

		CheckoutAmount amount = impl().getResolvedCheckoutAmount(courseId).orElseThrow();

		assertThat(amount.amount()).isEqualByComparingTo("0.00");
		assertThat(amount.currency()).isEqualTo(PlatformCurrency.DEFAULT_CURRENCY);
		assertThat(amount.billingPeriodId()).isNull();
		assertThat(amount.requiresManualQuote()).isFalse();
		// ADR-015 (Phase E review fix): a genuine FREE course must resolve
		// freePricing() = true - this is the ONLY field OrderService may gate
		// its auto-activation on, never amount.signum() == 0 alone.
		assertThat(amount.freePricing()).isTrue();
	}

	@Test
	void oneTimeResolvesToCoursePriceWithThePlatformDefaultCurrency() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.ONE_TIME, new BigDecimal("99.99"));
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

		CheckoutAmount amount = impl().getResolvedCheckoutAmount(courseId).orElseThrow();

		assertThat(amount.amount()).isEqualByComparingTo("99.99");
		assertThat(amount.currency()).isEqualTo(PlatformCurrency.DEFAULT_CURRENCY);
		assertThat(amount.billingPeriodId()).isNull();
		// ADR-015: ONE_TIME is never freePricing, even for a nonzero price -
		// see the dedicated zero-priced-ONE_TIME test below for the $0 case.
		assertThat(amount.freePricing()).isFalse();
	}

	/**
	 * Fix 1 (Phase E review, ADR-015): a {@code ONE_TIME} course whose {@code
	 * price} is (mis)configured to {@code $0} must still resolve {@code
	 * freePricing() = false} - {@code CourseLookupApiImpl} must never infer
	 * "free" from the amount alone. {@code OrderService} is what rejects this
	 * case as a misconfiguration; this test only proves the read-side
	 * resolution stays accurate for it.
	 */
	@Test
	void oneTimeWithAZeroPriceStillResolvesFreePricingFalse() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.ONE_TIME, BigDecimal.ZERO);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

		CheckoutAmount amount = impl().getResolvedCheckoutAmount(courseId).orElseThrow();

		assertThat(amount.amount()).isEqualByComparingTo("0.00");
		assertThat(amount.freePricing()).isFalse();
	}

	@Test
	void monthlyResolvesToTheCurrentOpenPeriodsAmountCurrencyAndId() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.MONTHLY, BigDecimal.ZERO);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId, null, "LKR",
				false);
		setId(configuration, UUID.randomUUID());
		CourseBillingPeriod period = new CourseBillingPeriod(TENANT_ID, configuration.getId(), new BigDecimal("30.00"),
				"LKR", Instant.now(), null);
		setId(period, UUID.randomUUID());
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.of(configuration));
		when(billingPeriodRepository.findCurrentOpenByBillingConfigurationId(configuration.getId()))
			.thenReturn(Optional.of(period));

		CheckoutAmount amount = impl().getResolvedCheckoutAmount(courseId).orElseThrow();

		assertThat(amount.amount()).isEqualByComparingTo("30.00");
		assertThat(amount.currency()).isEqualTo("LKR");
		assertThat(amount.billingPeriodId()).isEqualTo(period.getId());
		assertThat(amount.requiresManualQuote()).isFalse();
		assertThat(amount.freePricing()).isFalse();
	}

	@Test
	void sessionWithNoOpenPeriodThrowsRatherThanResolvingToZero() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.SESSION, BigDecimal.ZERO);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId,
				new BigDecimal("5.00"), "USD", false);
		setId(configuration, UUID.randomUUID());
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.of(configuration));
		when(billingPeriodRepository.findCurrentOpenByBillingConfigurationId(configuration.getId()))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> impl().getResolvedCheckoutAmount(courseId))
			.isInstanceOf(CourseBillingNotConfiguredException.class);
	}

	@Test
	void sessionWithNoBillingConfigurationAtAllThrows() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.SESSION, BigDecimal.ZERO);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> impl().getResolvedCheckoutAmount(courseId))
			.isInstanceOf(CourseBillingNotConfiguredException.class);
	}

	@Test
	void customResolvesToNullAmountWithRequiresManualQuoteTrueAndTheConfigurationsCurrency() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.CUSTOM, BigDecimal.ZERO);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId, null, "EUR",
				true);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.of(configuration));

		CheckoutAmount amount = impl().getResolvedCheckoutAmount(courseId).orElseThrow();

		assertThat(amount.amount()).isNull();
		assertThat(amount.currency()).isEqualTo("EUR");
		assertThat(amount.requiresManualQuote()).isTrue();
		assertThat(amount.billingPeriodId()).isNull();
		assertThat(amount.freePricing()).isFalse();
	}

	@Test
	void customWithNoBillingConfigurationAtAllThrows() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.CUSTOM, BigDecimal.ZERO);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> impl().getResolvedCheckoutAmount(courseId))
			.isInstanceOf(CourseBillingNotConfiguredException.class);
	}

	@Test
	void aNonexistentCourseIdResolvesToEmptyNotAnException() {
		UUID courseId = UUID.randomUUID();
		when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

		assertThat(impl().getResolvedCheckoutAmount(courseId)).isEmpty();
	}

	private Course courseFixture(UUID id, CoursePricingModel pricingModel, BigDecimal price) {
		Course course = new Course(TENANT_ID, UUID.randomUUID(), "Test Course", "test-course-" + UUID.randomUUID(),
				"Math", null, null, null, null, null, price, null, null, CourseStatus.PUBLIC);
		course.setPricingModel(pricingModel);
		setId(course, id);
		return course;
	}

	private static void setId(BaseEntity entity, UUID id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
