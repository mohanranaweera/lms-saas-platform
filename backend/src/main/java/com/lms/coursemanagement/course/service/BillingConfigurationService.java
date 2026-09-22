package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseBillingConfigurationChangedEvent;
import com.lms.coursemanagement.api.CourseBillingPeriodAddedEvent;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Wave 2's {@code course_billing_configuration}/{@code
 * course_billing_period} write and read paths - the course-owned billing
 * sub-aggregate for {@code MONTHLY}/{@code SESSION}/{@code CUSTOM} pricing
 * models (V38/V39). Every public method independently re-loads the owning
 * {@link Course} through {@link CourseRepository#findById} (tenant-scoped)
 * and re-runs {@link CourseAccessGuard#requireCourseAccess}, mirroring {@code
 * CourseModuleService}/{@code CourseLessonService}'s established
 * defense-in-depth discipline exactly - never trusts a caller-supplied
 * tenant/course-ownership claim.
 */
@Service
@Transactional
public class BillingConfigurationService {

	private final CourseRepository courseRepository;

	private final CourseBillingConfigurationRepository billingConfigurationRepository;

	private final CourseBillingPeriodRepository billingPeriodRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final TenantContext tenantContext;

	private final ApplicationEventPublisher eventPublisher;

	public BillingConfigurationService(CourseRepository courseRepository,
			CourseBillingConfigurationRepository billingConfigurationRepository,
			CourseBillingPeriodRepository billingPeriodRepository, CourseAccessGuard courseAccessGuard,
			TenantContext tenantContext, ApplicationEventPublisher eventPublisher) {
		this.courseRepository = courseRepository;
		this.billingConfigurationRepository = billingConfigurationRepository;
		this.billingPeriodRepository = billingPeriodRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.tenantContext = tenantContext;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Creates the course's one-and-only {@code course_billing_configuration}
	 * row, or updates it in place if one already exists (V38's {@code UNIQUE
	 * (tenant_id, course_id)} - "one configuration per course", never a second
	 * row). {@code sessionRate} is rejected (never silently ignored) unless
	 * the course's current {@link CoursePricingModel} is {@link
	 * CoursePricingModel#SESSION}; {@code requiresManualQuote = true} is
	 * rejected unless it is {@link CoursePricingModel#CUSTOM} - a caller
	 * setting a field that isn't meaningful for the course's own pricing
	 * model most likely made a mistake, so this is validated strictly rather
	 * than silently coerced/ignored.
	 */
	public CourseBillingConfigurationView createOrUpdateConfiguration(UUID courseId, BigDecimal sessionRate,
			String currency, boolean requiresManualQuote) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		validateAgainstPricingModel(course.getPricingModel(), sessionRate, requiresManualQuote);

		Optional<CourseBillingConfiguration> existing = billingConfigurationRepository.findByCourseId(courseId);
		CourseBillingConfiguration configuration;
		boolean created = existing.isEmpty();
		if (existing.isPresent()) {
			configuration = existing.get();
			configuration.update(sessionRate, currency, requiresManualQuote);
		}
		else {
			configuration = new CourseBillingConfiguration(tenantContext.getTenantId(), courseId, sessionRate,
					currency, requiresManualQuote);
			configuration = billingConfigurationRepository.save(configuration);
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		eventPublisher.publishEvent(new CourseBillingConfigurationChangedEvent(tenantContext.getTenantId(), courseId,
				principal.userId(), sessionRate, currency, requiresManualQuote, created, Instant.now()));

		return toConfigurationView(configuration);
	}

	@Transactional(readOnly = true)
	public CourseBillingConfigurationView getConfiguration(UUID courseId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		CourseBillingConfiguration configuration = billingConfigurationRepository.findByCourseId(courseId)
			.orElseThrow(() -> new NotFoundException("Course billing configuration not found"));
		return toConfigurationView(configuration);
	}

	/**
	 * Closes the current open period for this course's billing configuration
	 * (if any - {@code effectiveTo = effectiveFrom} of the new period, per
	 * this method's own documented "close-at-the-new-period's-start" choice,
	 * never a separate/earlier timestamp) and inserts the new, now-current
	 * period, in one transaction. {@code amount} on the closed row is never
	 * touched - {@code course_billing_period} is append-only (V39).
	 *
	 * <p>The close is explicitly {@code saveAndFlush}ed BEFORE the new period
	 * is inserted - Hibernate's default flush ordering runs every pending
	 * INSERT before any pending UPDATE within one flush regardless of the
	 * order they were called in application code, so without this explicit
	 * flush, the new (unconditionally {@code effective_to IS NULL}) row would
	 * be inserted while the row being closed is STILL {@code effective_to IS
	 * NULL} too, spuriously violating {@code uq_course_billing_period_current}
	 * even though there is no real concurrent race - found via this module's
	 * own {@code CourseBillingAndLifecycleIntegrationTest} surfacing a
	 * genuine {@code 409} on an ordinary, non-concurrent second call.
	 *
	 * <p>The new period's own insert is ALSO explicitly {@code saveAndFlush}ed,
	 * still inside this method's own try/catch - without that, a genuine
	 * concurrent race against {@code uq_course_billing_period_current} would
	 * only surface at Hibernate's deferred flush at transaction-commit time,
	 * OUTSIDE this method entirely, and be caught only by {@code
	 * GlobalExceptionHandler}'s generic {@link DataIntegrityViolationException}
	 * fallback rather than this method's own dedicated {@link
	 * ConflictException} mapping below - both map to the same correct {@code
	 * 409}, but only the explicit flush makes this method's own mapping the
	 * one that actually fires (verified by {@code
	 * CourseBillingAndLifecycleIntegrationTest
	 * #concurrentFirstBillingPeriodInsertsForTheSameCourseProduceExactlyOneSuccessAndOneCleanConflict}).
	 */
	public CourseBillingPeriodView addBillingPeriod(UUID courseId, BigDecimal amount, String currency,
			Instant effectiveFrom) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseBillingConfiguration configuration = billingConfigurationRepository.findByCourseId(courseId)
			.orElseThrow(() -> new NotFoundException(
					"Course billing configuration not found - create one before adding a billing period"));

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		try {
			BigDecimal previousAmount = billingPeriodRepository
				.findCurrentOpenByBillingConfigurationId(configuration.getId())
				.map(open -> {
					BigDecimal amt = open.getAmount();
					open.close(effectiveFrom);
					billingPeriodRepository.saveAndFlush(open);
					return amt;
				})
				.orElse(null);

			CourseBillingPeriod period = new CourseBillingPeriod(tenantContext.getTenantId(), configuration.getId(),
					amount, currency, effectiveFrom, principal.userId());
			period = billingPeriodRepository.saveAndFlush(period);

			eventPublisher.publishEvent(new CourseBillingPeriodAddedEvent(tenantContext.getTenantId(), courseId,
					configuration.getId(), period.getId(), principal.userId(), previousAmount, amount, currency,
					effectiveFrom, Instant.now()));

			return toPeriodView(period);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("A current billing period already exists for this course");
		}
	}

	@Transactional(readOnly = true)
	public CourseBillingPeriodView getCurrentPeriod(UUID courseId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		CourseBillingConfiguration configuration = billingConfigurationRepository.findByCourseId(courseId)
			.orElseThrow(() -> new NotFoundException("Course billing configuration not found"));
		CourseBillingPeriod period = billingPeriodRepository
			.findCurrentOpenByBillingConfigurationId(configuration.getId())
			.orElseThrow(() -> new NotFoundException("No current billing period configured for this course"));
		return toPeriodView(period);
	}

	@Transactional(readOnly = true)
	public Page<CourseBillingPeriodView> getPeriodHistory(UUID courseId, Pageable pageable) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		CourseBillingConfiguration configuration = billingConfigurationRepository.findByCourseId(courseId)
			.orElseThrow(() -> new NotFoundException("Course billing configuration not found"));
		return billingPeriodRepository.findByBillingConfigurationId(configuration.getId(), pageable)
			.map(BillingConfigurationService::toPeriodView);
	}

	/**
	 * {@code sessionRate} is only meaningful for {@link
	 * CoursePricingModel#SESSION}; {@code requiresManualQuote = true} is only
	 * meaningful for {@link CoursePricingModel#CUSTOM}. Either mismatch is
	 * rejected outright (never silently ignored/nulled) - documented decision
	 * for the plan's open "reject or ignore" judgment call, since silently
	 * dropping a caller-supplied value that doesn't apply is more likely to
	 * hide a caller mistake than help one.
	 */
	private void validateAgainstPricingModel(CoursePricingModel pricingModel, BigDecimal sessionRate,
			boolean requiresManualQuote) {
		if (sessionRate != null && pricingModel != CoursePricingModel.SESSION) {
			throw new InvalidBillingConfigurationException(
					"sessionRate may only be set for a course whose pricing model is SESSION");
		}
		if (requiresManualQuote && pricingModel != CoursePricingModel.CUSTOM) {
			throw new InvalidBillingConfigurationException(
					"requiresManualQuote may only be true for a course whose pricing model is CUSTOM");
		}
	}

	private Course loadCourse(UUID courseId) {
		return courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private static CourseBillingConfigurationView toConfigurationView(CourseBillingConfiguration configuration) {
		return new CourseBillingConfigurationView(configuration.getId(), configuration.getCourseId(),
				configuration.getSessionRate(), configuration.getCurrency(), configuration.isRequiresManualQuote(),
				configuration.getCreatedAt(), configuration.getUpdatedAt());
	}

	private static CourseBillingPeriodView toPeriodView(CourseBillingPeriod period) {
		return new CourseBillingPeriodView(period.getId(), period.getBillingConfigurationId(), period.getAmount(),
				period.getCurrency(), period.getEffectiveFrom(), period.getEffectiveTo(), period.getCreatedAt());
	}

}
