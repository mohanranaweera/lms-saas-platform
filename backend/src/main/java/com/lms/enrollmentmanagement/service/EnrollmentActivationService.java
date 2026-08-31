package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link EnrollmentActivationApi} - see that interface's javadoc
 * for the structurally-approved call sites and the defense-in-depth
 * re-verification requirement. The two reactivation methods below delegate
 * their actual {@code enrollment} mutation to {@link
 * ReactivationTransactionService}, a collaborator bean that now runs in this
 * class's own ambient transaction (see that class's javadoc for why) and
 * {@link EnrollmentActivationApi}'s "Transactional-boundary contract" section
 * for the full rationale.
 *
 * <p>The consolidated {@code activateOrReactivateFromConfirmedPayment}/{@code
 * activateOrReactivateFromApprovedSlip} methods (finding M2) own the
 * resolve-access-state-and-branch decision that used to be duplicated at
 * {@code PaymentConfirmationService}/{@code SlipReviewService} - this is why
 * this class now also depends on {@link EnrollmentAccessApi}, in addition to
 * the payment/slip verification and reactivation-transaction collaborators it
 * already had.
 */
@Service
public class EnrollmentActivationService implements EnrollmentActivationApi {

	private final EnrollmentRepository enrollmentRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final ReactivationTransactionService reactivationTransactionService;

	private final EnrollmentAccessApi enrollmentAccessApi;

	public EnrollmentActivationService(EnrollmentRepository enrollmentRepository, PaymentStatusApi paymentStatusApi,
			SlipStatusApi slipStatusApi, CourseLookupApi courseLookupApi, TenantContext tenantContext,
			ReactivationTransactionService reactivationTransactionService, EnrollmentAccessApi enrollmentAccessApi) {
		this.enrollmentRepository = enrollmentRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.reactivationTransactionService = reactivationTransactionService;
		this.enrollmentAccessApi = enrollmentAccessApi;
	}

	@Override
	@Transactional
	public void activateFromConfirmedPayment(UUID paymentId, UUID studentId, UUID courseId) {
		if (!paymentStatusApi.isConfirmedForCurrentTenant(paymentId)) {
			throw new IllegalStateException(
					"Refusing to activate enrollment: payment " + paymentId + " is not a CONFIRMED payment in the "
							+ "current tenant context - independent re-verification failed");
		}
		if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
			// Already activated (e.g. a retried webhook for the same
			// payment, or a genuinely repeated activation attempt) - a
			// no-op, per uq_enrollment_tenant_student_course_current's
			// idempotency guarantee (V22).
			return;
		}
		try {
			Instant now = Instant.now();
			Enrollment enrollment = Enrollment.fromConfirmedPayment(tenantContext.getTenantId(), studentId, courseId,
					paymentId, computeAccessExpiresAt(courseId, now));
			enrollmentRepository.save(enrollment);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race against a concurrent duplicate activation attempt
			// for the same (tenant, student, course) - the unique
			// constraint already has a row, which is success, not failure,
			// per the idempotent-activation requirement (plan §8/§15).
		}
	}

	@Override
	@Transactional
	public void activateFromApprovedSlip(UUID slipId, UUID studentId, UUID courseId) {
		if (!slipStatusApi.isApprovedForCurrentTenant(slipId)) {
			throw new IllegalStateException(
					"Refusing to activate enrollment: slip " + slipId + " is not an APPROVED payment slip in the "
							+ "current tenant context - independent re-verification failed");
		}
		if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
			// Already activated (e.g. a retried approval call, or a
			// genuinely repeated activation attempt) - a no-op, per
			// uq_enrollment_tenant_student_course_current's idempotency
			// guarantee (V22).
			return;
		}
		try {
			Instant now = Instant.now();
			Enrollment enrollment = Enrollment.fromApprovedSlip(tenantContext.getTenantId(), studentId, courseId,
					slipId, computeAccessExpiresAt(courseId, now));
			enrollmentRepository.save(enrollment);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race against a concurrent duplicate activation attempt
			// for the same (tenant, student, course) - the unique
			// constraint already has a row, which is success, not failure,
			// per the idempotent-activation requirement (plan §8/§15).
		}
	}

	/**
	 * Deliberately carries NO {@code @Transactional} annotation of its own -
	 * see {@link EnrollmentActivationApi#reactivateFromConfirmedPayment}'s
	 * "Transactional-boundary contract" javadoc section and {@link
	 * ReactivationTransactionService}'s class javadoc for the full rationale,
	 * including a documented, reverted attempt to add one here. With no
	 * annotation, this method's {@code PaymentStatusApi} re-verification and
	 * the mutation delegated to {@link ReactivationTransactionService} both
	 * execute as ordinary Java calls inside the CALLER's (always {@code
	 * PaymentConfirmationService}) already-open transaction - a refusal here
	 * crosses no {@code @Transactional} proxy boundary of its own, so it
	 * reaches the caller's {@code catch (IllegalStateException)} without
	 * Spring marking that shared transaction rollback-only first.
	 */
	@Override
	public void reactivateFromConfirmedPayment(UUID paymentId, UUID orderId, UUID studentId, UUID courseId) {
		if (!paymentStatusApi.isConfirmedForCurrentTenant(paymentId)) {
			throw new IllegalStateException(
					"Refusing to reactivate enrollment: payment " + paymentId + " is not a CONFIRMED payment in the "
							+ "current tenant context - independent re-verification failed");
		}
		Instant accessExpiresAt = computeAccessExpiresAt(courseId, Instant.now());
		reactivationTransactionService.reactivateFromConfirmedPayment(paymentId, orderId, studentId, courseId,
				accessExpiresAt);
	}

	/** Mirrors {@link #reactivateFromConfirmedPayment}'s exact transactional-boundary contract - see its javadoc. */
	@Override
	public void reactivateFromApprovedSlip(UUID slipId, UUID orderId, UUID studentId, UUID courseId) {
		if (!slipStatusApi.isApprovedForCurrentTenant(slipId)) {
			throw new IllegalStateException(
					"Refusing to reactivate enrollment: slip " + slipId + " is not an APPROVED payment slip in the "
							+ "current tenant context - independent re-verification failed");
		}
		Instant accessExpiresAt = computeAccessExpiresAt(courseId, Instant.now());
		reactivationTransactionService.reactivateFromApprovedSlip(slipId, orderId, studentId, courseId,
				accessExpiresAt);
	}

	/**
	 * @see EnrollmentActivationApi#activateOrReactivateFromConfirmedPayment(UUID, UUID, UUID, UUID)
	 */
	@Override
	public void activateOrReactivateFromConfirmedPayment(UUID paymentId, UUID orderId, UUID studentId,
			UUID courseId) {
		EnrollmentAccessStateType accessState = enrollmentAccessApi.resolveAccessState(studentId, courseId).state();
		if (accessState == EnrollmentAccessStateType.NEVER_ENROLLED) {
			activateFromConfirmedPayment(paymentId, studentId, courseId);
		}
		else {
			reactivateFromConfirmedPayment(paymentId, orderId, studentId, courseId);
		}
	}

	/**
	 * @see EnrollmentActivationApi#activateOrReactivateFromApprovedSlip(UUID, UUID, UUID, UUID)
	 */
	@Override
	public void activateOrReactivateFromApprovedSlip(UUID slipId, UUID orderId, UUID studentId, UUID courseId) {
		EnrollmentAccessStateType accessState = enrollmentAccessApi.resolveAccessState(studentId, courseId).state();
		if (accessState == EnrollmentAccessStateType.NEVER_ENROLLED) {
			activateFromApprovedSlip(slipId, studentId, courseId);
		}
		else {
			reactivateFromApprovedSlip(slipId, orderId, studentId, courseId);
		}
	}

	/**
	 * Reads {@code course.access_duration_days} ONCE, at (re)activation
	 * time, and snapshots the result - never re-read later (plan §12). A
	 * course that cannot be resolved at all (should not happen - {@code
	 * courseId} is always already-validated by {@code OrderService} at order
	 * creation time) degrades safely to {@code null} (lifetime access)
	 * rather than throwing, since a payment/slip has already been confirmed/
	 * approved by this point and enrollment activation must not be blocked
	 * by a course-lookup anomaly.
	 */
	private Instant computeAccessExpiresAt(UUID courseId, Instant activatedAt) {
		return courseLookupApi.getAccessDurationDays(courseId)
			.map(CourseAccessWindow::accessDurationDays)
			.map(days -> activatedAt.plus(days, ChronoUnit.DAYS))
			.orElse(null);
	}

}
