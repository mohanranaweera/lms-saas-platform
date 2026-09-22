package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.persistence.BaseEntity;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Mockito-only unit coverage for {@link BillingConfigurationService}
 * (Wave 2), mirroring {@code CourseServiceTest}'s style: focuses on the
 * hardest-to-get-wrong behaviors - {@code sessionRate}/{@code
 * requiresManualQuote} validation against the course's pricing model, a
 * billing-period add closing the prior open period without mutating its
 * amount, and a partial-unique-index race being mapped to a clean {@link
 * ConflictException}.
 */
@ExtendWith(MockitoExtension.class)
class BillingConfigurationServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private CourseBillingConfigurationRepository billingConfigurationRepository;

	@Mock
	private CourseBillingPeriodRepository billingPeriodRepository;

	@Mock
	private CourseAccessGuard courseAccessGuard;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private BillingConfigurationService service;

	@BeforeEach
	void setUp() {
		service = new BillingConfigurationService(courseRepository, billingConfigurationRepository,
				billingPeriodRepository, courseAccessGuard, tenantContext, eventPublisher);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void sessionRateForANonSessionPricedCourseIsRejected() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.ONE_TIME);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

		assertThatThrownBy(
				() -> service.createOrUpdateConfiguration(courseId, new BigDecimal("10.00"), "USD", false))
			.isInstanceOf(InvalidBillingConfigurationException.class);

		verify(billingConfigurationRepository, never()).save(any());
	}

	@Test
	void requiresManualQuoteForANonCustomPricedCourseIsRejected() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.SESSION);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

		assertThatThrownBy(() -> service.createOrUpdateConfiguration(courseId, null, "USD", true))
			.isInstanceOf(InvalidBillingConfigurationException.class);

		verify(billingConfigurationRepository, never()).save(any());
	}

	@Test
	void createsANewConfigurationWhenNoneExistsAndPublishesAnEvent() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.SESSION);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.empty());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(billingConfigurationRepository.save(any(CourseBillingConfiguration.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		CourseBillingConfigurationView view = service.createOrUpdateConfiguration(courseId, new BigDecimal("15.00"),
				"USD", false);

		assertThat(view.sessionRate()).isEqualByComparingTo("15.00");
		verify(billingConfigurationRepository, times(1)).save(any());
		verify(eventPublisher).publishEvent(
				any(com.lms.coursemanagement.api.CourseBillingConfigurationChangedEvent.class));
	}

	@Test
	void updatesAnExistingConfigurationInPlaceRatherThanCreatingASecondRow() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.SESSION);
		CourseBillingConfiguration existing = new CourseBillingConfiguration(TENANT_ID, courseId,
				new BigDecimal("10.00"), "USD", false);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.of(existing));

		service.createOrUpdateConfiguration(courseId, new BigDecimal("20.00"), "USD", false);

		verify(billingConfigurationRepository, never()).save(any());
		assertThat(existing.getSessionRate()).isEqualByComparingTo("20.00");
	}

	@Test
	void addingABillingPeriodClosesThePriorOpenPeriodWithoutMutatingItsStoredAmount() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.MONTHLY);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId, null, "USD",
				false);
		setId(configuration, UUID.randomUUID());
		CourseBillingPeriod openPeriod = new CourseBillingPeriod(TENANT_ID, configuration.getId(),
				new BigDecimal("50.00"), "USD", Instant.now().minusSeconds(86400), UUID.randomUUID());
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.of(configuration));
		when(billingPeriodRepository.findCurrentOpenByBillingConfigurationId(configuration.getId()))
			.thenReturn(Optional.of(openPeriod));
		when(billingPeriodRepository.saveAndFlush(any(CourseBillingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

		Instant effectiveFrom = Instant.now();
		CourseBillingPeriodView view = service.addBillingPeriod(courseId, new BigDecimal("75.00"), "USD",
				effectiveFrom);

		assertThat(view.amount()).isEqualByComparingTo("75.00");
		assertThat(openPeriod.getEffectiveTo()).isEqualTo(effectiveFrom);
		assertThat(openPeriod.getAmount()).isEqualByComparingTo("50.00"); // never mutated
		ArgumentCaptor<CourseBillingPeriod> captor = ArgumentCaptor.forClass(CourseBillingPeriod.class);
		// Both the "close the prior open period" half AND the "insert the
		// new period" half are now saveAndFlush()ed (not just save()d)
		// inside this method's own try/catch - see BillingConfigurationService
		// #addBillingPeriod's javadoc for why (Wave 2 QA gap fix: the flush
		// is what makes the service's own ConflictException mapping actually
		// fire for a genuine concurrent-insert race, instead of only
		// GlobalExceptionHandler's generic fallback at commit time). Two
		// calls total: first the closed prior period, then the new one.
		verify(billingPeriodRepository, times(2)).saveAndFlush(captor.capture());
		assertThat(captor.getAllValues()).hasSize(2);
		assertThat(captor.getAllValues().get(1).getAmount()).isEqualByComparingTo("75.00");
	}

	@Test
	void addingABillingPeriodWithNoConfigurationYetIsRejected() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.MONTHLY);
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.addBillingPeriod(courseId, new BigDecimal("10.00"), "USD", Instant.now()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void aPartialUniqueIndexRaceOnAddBillingPeriodIsMappedToAConflictException() {
		UUID courseId = UUID.randomUUID();
		Course course = courseFixture(courseId, CoursePricingModel.MONTHLY);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId, null, "USD",
				false);
		setId(configuration, UUID.randomUUID());
		when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseId(courseId)).thenReturn(Optional.of(configuration));
		when(billingPeriodRepository.findCurrentOpenByBillingConfigurationId(configuration.getId()))
			.thenReturn(Optional.empty());
		when(billingPeriodRepository.saveAndFlush(any(CourseBillingPeriod.class)))
			.thenThrow(new DataIntegrityViolationException("uq_course_billing_period_current"));

		assertThatThrownBy(
				() -> service.addBillingPeriod(courseId, new BigDecimal("10.00"), "USD", Instant.now()))
			.isInstanceOf(ConflictException.class);
	}

	private Course courseFixture(UUID id, CoursePricingModel pricingModel) {
		Course course = new Course(TENANT_ID, UUID.randomUUID(), "Test Course", "test-course-" + UUID.randomUUID(),
				"Math", null, null, null, null, null, new BigDecimal("10.00"), null, null, CourseStatus.DRAFT);
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
