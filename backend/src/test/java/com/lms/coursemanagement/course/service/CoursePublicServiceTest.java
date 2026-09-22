package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.persistence.BaseEntity;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Mockito-only unit coverage for {@link CoursePublicService} (MVP-008),
 * required by the module plan §18 ("Draft/Private courses never returned
 * from the storefront-scoped read method, called directly - not via HTTP").
 * The real HTTP-layer equivalent lives in {@code
 * CoursePublicStorefrontIntegrationTest}; this class isolates that the
 * service queries the repository via the inherited, tenant-scoped {@code
 * findAll(Specification, Pageable)} - never a broader read followed by a
 * post-fetch filter - and that the public projection never leaks {@code
 * teacherId} or audit columns. The actual PUBLIC-only predicate content of
 * the {@code Specification} passed to the repository is proven at the
 * integration level ({@code CoursePublicStorefrontIntegrationTest}), not
 * here - a Mockito mock cannot meaningfully evaluate a JPA Criteria
 * predicate without a real {@code EntityManager}.
 */
@ExtendWith(MockitoExtension.class)
class CoursePublicServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private CourseBillingConfigurationRepository billingConfigurationRepository;

	@Mock
	private CourseBillingPeriodRepository billingPeriodRepository;

	private CoursePublicService coursePublicService;

	@BeforeEach
	void setUp() {
		CourseCheckoutAmountResolver resolver = new CourseCheckoutAmountResolver(billingConfigurationRepository,
				billingPeriodRepository);
		coursePublicService = new CoursePublicService(courseRepository, resolver);
	}

	@Test
	void listPublishedCoursesQueriesTheRepositoryViaTheTenantScopedSpecificationPageableFinder() {
		Course published = courseFixture("Public Course", "public-course", CourseStatus.PUBLIC);
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(published), pageable, 1));

		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable);

		verify(courseRepository).findAll(any(Specification.class), any(Pageable.class));
		assertThat(page.content()).hasSize(1);
		assertThat(page.content().get(0).slug()).isEqualTo("public-course");
		assertThat(page.totalElements()).isEqualTo(1);
	}

	@Test
	void listPublishedCoursesReturnsExactlyWhatTheRepositoryReturnsNeverMore() {
		// The service performs no additional in-memory filtering of its own -
		// if the repository (correctly, per its own PUBLIC-only Specification)
		// returns nothing, the service returns nothing. This is the "never a
		// post-fetch filter" half of the plan §15(d) requirement, verified at
		// the unit level.
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable);

		assertThat(page.content()).isEmpty();
		assertThat(page.totalElements()).isZero();
	}

	@Test
	void publicProjectionNeverIncludesTeacherId() {
		Course published = courseFixture("Public Course", "public-course", CourseStatus.PUBLIC);
		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(published), pageable, 1));

		PublicCourseView view = coursePublicService.listPublishedCourses(pageable).content().get(0);

		// PublicCourseView has no teacherId component at all - this is a
		// compile-time guarantee, not a runtime check, but asserting the
		// projected fields here documents that guarantee alongside the rest
		// of this test class rather than leaving it implicit.
		assertThat(view.name()).isEqualTo("Public Course");
		assertThat(view.slug()).isEqualTo("public-course");
	}

	@Test
	void listPublishedCoursesClampsAnOversizedRequestedPageSizeToTheServerSideMaximum() {
		Pageable oversized = PageRequest.of(0, 999_999);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenAnswer(invocation -> {
				Pageable used = invocation.getArgument(1);
				return new PageImpl<Course>(List.of(), used, 0);
			});

		coursePublicService.listPublishedCourses(oversized);

		org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
		verify(courseRepository).findAll(any(Specification.class), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	void getPublishedCourseBySlugQueriesTheRepositoryScopedToPublicStatusOnly() {
		Course published = courseFixture("Public Course", "public-course", CourseStatus.PUBLIC);
		when(courseRepository.findBySlugAndStatus("public-course", CourseStatus.PUBLIC))
			.thenReturn(Optional.of(published));

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("public-course");

		verify(courseRepository).findBySlugAndStatus("public-course", CourseStatus.PUBLIC);
		assertThat(view.slug()).isEqualTo("public-course");
	}

	@Test
	void getPublishedCourseBySlugThrowsGenericNotFoundWhenTheRepositoryFindsNothing() {
		// Covers both a truly nonexistent slug AND a real DRAFT/PRIVATE slug
		// in the same tenant identically - findBySlugAndStatus's own
		// PUBLIC-scoped Specification means either case surfaces here as an
		// empty Optional, so this service never has the information needed
		// to distinguish them even if it wanted to (plan §13/§15(d)'s
		// anti-enumeration guarantee, enforced by construction).
		when(courseRepository.findBySlugAndStatus("draft-or-missing", CourseStatus.PUBLIC))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> coursePublicService.getPublishedCourseBySlug("draft-or-missing"))
			.isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// Pricing-model-aware resolved amount (Wave 2 QA gap fix).
	// ------------------------------------------------------------------

	@Test
	void freePricedCourseResolvesToZeroAmount() {
		Course course = courseFixture("Free Course", "free-course", CourseStatus.PUBLIC);
		course.setPricingModel(CoursePricingModel.FREE);
		when(courseRepository.findBySlugAndStatus("free-course", CourseStatus.PUBLIC)).thenReturn(Optional.of(course));

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("free-course");

		assertThat(view.pricingModel()).isEqualTo(CoursePricingModel.FREE);
		assertThat(view.resolvedAmount()).isEqualByComparingTo("0.00");
		assertThat(view.requiresManualQuote()).isFalse();
	}

	@Test
	void oneTimePricedCourseResolvesToItsStaticPrice() {
		Course course = courseFixture("Paid Course", "paid-course", CourseStatus.PUBLIC);
		when(courseRepository.findBySlugAndStatus("paid-course", CourseStatus.PUBLIC)).thenReturn(Optional.of(course));

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("paid-course");

		assertThat(view.pricingModel()).isEqualTo(CoursePricingModel.ONE_TIME);
		assertThat(view.resolvedAmount()).isEqualByComparingTo("10.00");
	}

	@Test
	void monthlyPricedCourseWithAnOpenBillingPeriodResolvesToItsCurrentAmount() {
		Course course = courseFixture("Monthly Course", "monthly-course", CourseStatus.PUBLIC);
		course.setPricingModel(CoursePricingModel.MONTHLY);
		UUID courseId = UUID.randomUUID();
		setId(course, courseId);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId, null, "LKR",
				false);
		setId(configuration, UUID.randomUUID());
		CourseBillingPeriod period = new CourseBillingPeriod(TENANT_ID, configuration.getId(), new BigDecimal("30.00"),
				"LKR", Instant.now(), null);
		setId(period, UUID.randomUUID());
		when(courseRepository.findBySlugAndStatus("monthly-course", CourseStatus.PUBLIC))
			.thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseIdIn(Set.of(courseId))).thenReturn(List.of(configuration));
		when(billingPeriodRepository.findCurrentOpenByBillingConfigurationIdIn(Set.of(configuration.getId())))
			.thenReturn(List.of(period));

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("monthly-course");

		assertThat(view.resolvedAmount()).isEqualByComparingTo("30.00");
		assertThat(view.currency()).isEqualTo("LKR");
		assertThat(view.requiresManualQuote()).isFalse();
	}

	@Test
	void monthlyPricedCourseWithNoBillingConfiguredYetResolvesToNullRatherThanErroringOrZero() {
		Course course = courseFixture("Not Yet Configured", "not-yet-configured", CourseStatus.PUBLIC);
		course.setPricingModel(CoursePricingModel.MONTHLY);
		UUID courseId = UUID.randomUUID();
		setId(course, courseId);
		when(courseRepository.findBySlugAndStatus("not-yet-configured", CourseStatus.PUBLIC))
			.thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseIdIn(Set.of(courseId))).thenReturn(List.of());

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("not-yet-configured");

		assertThat(view.pricingModel()).isEqualTo(CoursePricingModel.MONTHLY);
		assertThat(view.resolvedAmount()).isNull();
		assertThat(view.currency()).isNull();
		assertThat(view.requiresManualQuote()).isFalse();
	}

	@Test
	void customPricedCourseResolvesToNullAmountWithRequiresManualQuoteTrue() {
		Course course = courseFixture("Custom Course", "custom-course", CourseStatus.PUBLIC);
		course.setPricingModel(CoursePricingModel.CUSTOM);
		UUID courseId = UUID.randomUUID();
		setId(course, courseId);
		CourseBillingConfiguration configuration = new CourseBillingConfiguration(TENANT_ID, courseId, null, "USD",
				true);
		when(courseRepository.findBySlugAndStatus("custom-course", CourseStatus.PUBLIC)).thenReturn(Optional.of(course));
		when(billingConfigurationRepository.findByCourseIdIn(Set.of(courseId))).thenReturn(List.of(configuration));

		PublicCourseView view = coursePublicService.getPublishedCourseBySlug("custom-course");

		assertThat(view.resolvedAmount()).isNull();
		assertThat(view.currency()).isEqualTo("USD");
		assertThat(view.requiresManualQuote()).isTrue();
	}

	@Test
	void listingMultipleMonthlyPricedCoursesResolvesBillingInOneBatchedQueryPairNeverOnePerCourse() {
		Course courseOne = courseFixture("Monthly One", "monthly-one", CourseStatus.PUBLIC);
		courseOne.setPricingModel(CoursePricingModel.MONTHLY);
		UUID courseOneId = UUID.randomUUID();
		setId(courseOne, courseOneId);
		Course courseTwo = courseFixture("Monthly Two", "monthly-two", CourseStatus.PUBLIC);
		courseTwo.setPricingModel(CoursePricingModel.MONTHLY);
		UUID courseTwoId = UUID.randomUUID();
		setId(courseTwo, courseTwoId);

		CourseBillingConfiguration configurationOne = new CourseBillingConfiguration(TENANT_ID, courseOneId, null,
				"LKR", false);
		setId(configurationOne, UUID.randomUUID());
		CourseBillingConfiguration configurationTwo = new CourseBillingConfiguration(TENANT_ID, courseTwoId, null,
				"LKR", false);
		setId(configurationTwo, UUID.randomUUID());
		CourseBillingPeriod periodOne = new CourseBillingPeriod(TENANT_ID, configurationOne.getId(),
				new BigDecimal("15.00"), "LKR", Instant.now(), null);
		CourseBillingPeriod periodTwo = new CourseBillingPeriod(TENANT_ID, configurationTwo.getId(),
				new BigDecimal("25.00"), "LKR", Instant.now(), null);

		Pageable pageable = PageRequest.of(0, 20);
		when(courseRepository.findAll(any(Specification.class), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(courseOne, courseTwo), pageable, 2));
		when(billingConfigurationRepository.findByCourseIdIn(Set.of(courseOneId, courseTwoId)))
			.thenReturn(List.of(configurationOne, configurationTwo));
		when(billingPeriodRepository
			.findCurrentOpenByBillingConfigurationIdIn(Set.of(configurationOne.getId(), configurationTwo.getId())))
			.thenReturn(List.of(periodOne, periodTwo));

		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable);

		assertThat(page.content()).hasSize(2);
		assertThat(page.content()).extracting(PublicCourseView::resolvedAmount)
			.containsExactlyInAnyOrder(new BigDecimal("15.00"), new BigDecimal("25.00"));
		// The N+1 guard: exactly one batched call each, never one per course.
		verify(billingConfigurationRepository, times(1)).findByCourseIdIn(any());
		verify(billingPeriodRepository, times(1)).findCurrentOpenByBillingConfigurationIdIn(any());
	}

	private static Course courseFixture(String name, String slug, CourseStatus status) {
		return new Course(TENANT_ID, UUID.randomUUID(), name, slug, "Math", null, null, null, null, null,
				new BigDecimal("10.00"), null, null, status);
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
