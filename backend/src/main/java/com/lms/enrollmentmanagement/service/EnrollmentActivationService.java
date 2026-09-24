package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(EnrollmentActivationService.class);

	private final EnrollmentRepository enrollmentRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final ReactivationTransactionService reactivationTransactionService;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final AuditLogApi auditLogApi;

	private final PermissionCheckService permissionCheckService;

	private final StudentLookupApi studentLookupApi;

	public EnrollmentActivationService(EnrollmentRepository enrollmentRepository, PaymentStatusApi paymentStatusApi,
			SlipStatusApi slipStatusApi, CourseLookupApi courseLookupApi, TenantContext tenantContext,
			ReactivationTransactionService reactivationTransactionService, EnrollmentAccessApi enrollmentAccessApi,
			AuditLogApi auditLogApi, PermissionCheckService permissionCheckService, StudentLookupApi studentLookupApi) {
		this.enrollmentRepository = enrollmentRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.reactivationTransactionService = reactivationTransactionService;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.auditLogApi = auditLogApi;
		this.permissionCheckService = permissionCheckService;
		this.studentLookupApi = studentLookupApi;
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
	 * @see EnrollmentActivationApi#fromApprovedManualEvidence(UUID, UUID, UUID, UUID)
	 */
	@Override
	@Transactional
	public void fromApprovedManualEvidence(UUID paymentId, UUID orderId, UUID studentId, UUID courseId) {
		activateOrReactivateFromConfirmedPayment(paymentId, orderId, studentId, courseId);
	}

	/**
	 * Wave 3 (Student actions - staff "revoke enrollment", change-controlled
	 * per {@code .claude/rules/payments.md} §7, approved per ADR-016 - see
	 * {@code docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md}) -
	 * a new, narrow call site into {@link Enrollment#revoke(UUID, String)},
	 * which itself calls the existing {@link Enrollment#supersede()}
	 * mutation with no replacement row. No new {@code EnrollmentStatus}
	 * value, no ledger/payment write. Reactivation afterward reuses the
	 * existing {@code reactivation_request} flow unchanged (a revoked
	 * enrollment's access state resolves to {@code EXPIRED}-shaped/no-longer
	 * -current, the same way a naturally expired one does, so {@code
	 * OrderService}'s existing reactivation gate already covers it with no
	 * further code change here).
	 *
	 * <p>Declared on {@link EnrollmentActivationApi} (see that interface's
	 * javadoc for why) even though its only real caller is this same
	 * module's own {@code EnrollmentController} - kept on the stable
	 * interface type so this codebase's existing {@code @MockitoBean
	 * EnrollmentActivationApi} test-override pattern keeps working.
	 * @throws NotFoundException if {@code enrollmentId} does not resolve to
	 * a CURRENT enrollment row in the caller's own resolved tenant (a
	 * cross-tenant or already-superseded id is indistinguishable from "does
	 * not exist" here, mirroring every other owner/tenant-scoped lookup in
	 * this codebase).
	 * @throws ConflictException if {@code reason} is blank.
	 */
	@Override
	@Transactional
	public void revoke(UUID enrollmentId, String reason) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		if (reason == null || reason.isBlank()) {
			throw new ConflictException("A reason is required to revoke an enrollment");
		}
		// TenantAwareRepository scopes findById to the resolved tenant
		// context already - a cross-tenant enrollmentId is structurally
		// invisible here, surfacing as 404, never a cross-tenant mutation.
		Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
			.orElseThrow(() -> new NotFoundException("Enrollment not found"));
		if (enrollment.getSupersededAt() != null) {
			// The row genuinely exists in this tenant but is no longer
			// current (already revoked, or superseded by a reactivation) -
			// 409, not 404, since the id itself resolved fine; this is the
			// "unauthorized state transition: revoking an already-revoked
			// enrollment" case named explicitly in the wave-03 test plan.
			throw new ConflictException("This enrollment is not currently active and cannot be revoked");
		}

		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		enrollment.revoke(actorId, reason);
		try {
			enrollmentRepository.save(enrollment);
		}
		catch (org.springframework.dao.OptimisticLockingFailureException ex) {
			// Wave 3 fix-pass (security/architecture review) - two
			// near-simultaneous revoke calls for the same row both passed the
			// supersededAt == null read-check above; V48's @Version column
			// (see Enrollment's own javadoc) lets Hibernate detect the race
			// on the losing transaction's UPDATE (zero rows affected) rather
			// than silently letting the later-committing revoke's
			// revoked_by/revoke_reason overwrite the earlier one's
			// attribution. Surfaced as a clean 409, never a 500 - the
			// caller's retry will correctly observe the already-revoked
			// state via the supersededAt != null branch above.
			throw new ConflictException("This enrollment was concurrently modified by another request - please retry");
		}

		auditLogApi.record(new AuditLogEntry(actorId, "enrollment.revoked", "enrollment", enrollmentId, reason, null));
		// Second audit row, same transaction, same action - targets the
		// student's own profile (rather than the enrollment row above) so this
		// revocation is discoverable via StudentService#listActivity /
		// GET /students/{id}/activity, per Wave 3 fix-pass (security review,
		// "enroll/revoke audit entries invisible on the student's own Activity
		// tab"). The enrollment-targeted row above is untouched - both are
		// kept, each serving its own traceability purpose; audit rows are
		// append-only, so this is an additional fact recorded, not a
		// correction of the first.
		//
		// enrollment.getStudentId() is the opaque cross-domain studentId
		// (tenant_user.id, per StudentLookupApi's own javadoc) - NOT the
		// student_profile id that GET /students/{id}/activity's {id} path
		// segment and StudentService#listActivity's targetId actually filter
		// by. Resolve the real student_profile id via StudentLookupApi
		// (batch-shaped, mirrors CourseRosterService's own established use of
		// getStudentSummariesByUserId) before writing this second row.
		resolveStudentProfileId(enrollment.getStudentId()).ifPresentOrElse(
				studentProfileId -> auditLogApi.record(new AuditLogEntry(actorId, "enrollment.revoked",
						"student_profile", studentProfileId, reason,
						java.util.Map.of("enrollmentId", enrollmentId.toString()))),
				() -> log.atWarn()
					.setMessage("enrollment.revoked_student_profile_audit_row_skipped")
					.addKeyValue("actor", actorId)
					.addKeyValue("tenantId", tenantContext.getTenantId())
					.addKeyValue("enrollmentId", enrollmentId)
					.addKeyValue("studentId", enrollment.getStudentId())
					.addKeyValue("reason", "studentId did not resolve to a student_profile row in this tenant")
					.log());
	}

	/**
	 * Resolves a {@code tenant_user.id} (the opaque cross-domain {@code
	 * studentId} this class's own {@link Enrollment} rows are keyed by) to its
	 * owning {@code student_profile.id}, scoped to the current tenant, via
	 * {@link StudentLookupApi#getStudentSummariesByUserId} (batch-shaped;
	 * called here with a single-element list, mirroring {@code
	 * CourseRosterService}'s established use of the same method). Returns
	 * empty if the id does not resolve to a real, current-tenant {@code
	 * student_profile} row - structurally near-unreachable (this method is
	 * only ever called with a studentId this same transaction just proved
	 * belongs to a real, current-tenant {@code Enrollment} row), but handled
	 * defensively rather than assumed, same discipline as {@code
	 * ManualEnrollmentService#grantEnrollment}'s own "log, don't roll back a
	 * real state change" handling for its structurally-near-unreachable case.
	 */
	private java.util.Optional<UUID> resolveStudentProfileId(UUID studentId) {
		List<StudentSummary> summaries = studentLookupApi.getStudentSummariesByUserId(List.of(studentId));
		return summaries.stream().findFirst().map(StudentSummary::studentProfileId);
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
