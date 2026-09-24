package com.lms.paymentmanagement.order.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.ManualEnrollmentApi;
import com.lms.paymentmanagement.api.ManualEnrollmentResult;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link ManualEnrollmentApi} (Wave 3 - Student actions, staff
 * "enroll student in course", change-controlled per {@code
 * .claude/rules/payments.md} §7, approved per {@code
 * docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md}). Mirrors {@code
 * OrderService#activateFreeCheckout}'s exact shape (same {@code
 * Order}-create -&gt; {@code Payment}-create -&gt; {@code confirm()} -&gt;
 * activate sequence, all in one transaction, per {@code
 * .claude/rules/backend.md}'s payment-activation atomicity rule) - the
 * amount recorded is the course's genuinely resolved checkout amount (never
 * a synthesized {@code $0}, unless the course itself is priced {@code
 * FREE}), since this is real payment/ledger evidence, not a bypass.
 *
 * <h2>Deviation from the wave-03 plan doc's literal "method/evidence enum"
 * wording</h2>
 * The plan describes this as extending {@code payment}'s "method/evidence
 * enum" with {@code STAFF_GRANTED". The actual, already-shared {@code
 * payment} schema (V19) has no such enum column - see
 * {@code V47__add_enrollment_staff_granted_evidence.sql}'s header comment
 * for the full explanation and the precedent this mirrors instead ({@code
 * OrderService#activateFreeCheckout}'s {@code "FREE-" + paymentId}
 * synthesized {@code gateway_reference}). This class uses the equivalent
 * {@code "STAFF_GRANTED-" + paymentId} convention, plus the new {@code
 * staff_grant_reason} column, as the evidence trail.
 *
 * <p>Independently re-requires {@code PAYMENTS_SLIPS}/{@code APPROVE} -
 * mirrors {@code SlipReviewService#approve}'s exact gate - in addition to
 * {@code user-management}'s own {@code STUDENTS}/{@code CREATE_EDIT} check
 * at the calling {@code StudentService}, per the wave-03 plan §4's "exact
 * second gate decided during backend implementation, mirroring how
 * slip-approval is gated".
 */
@Service
@Transactional
public class ManualEnrollmentService implements ManualEnrollmentApi {

	private final StudentOrderRepository studentOrderRepository;

	private final PaymentRepository paymentRepository;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final EnrollmentActivationApi enrollmentActivationApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final LedgerEntryApi ledgerEntryApi;

	private final StudentLookupApi studentLookupApi;

	public ManualEnrollmentService(StudentOrderRepository studentOrderRepository, PaymentRepository paymentRepository,
			CourseLookupApi courseLookupApi, TenantContext tenantContext, EnrollmentAccessApi enrollmentAccessApi,
			EnrollmentActivationApi enrollmentActivationApi, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, LedgerEntryApi ledgerEntryApi, StudentLookupApi studentLookupApi) {
		this.studentOrderRepository = studentOrderRepository;
		this.paymentRepository = paymentRepository;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.enrollmentActivationApi = enrollmentActivationApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.ledgerEntryApi = ledgerEntryApi;
		this.studentLookupApi = studentLookupApi;
	}

	@Override
	public ManualEnrollmentResult grantEnrollment(UUID studentId, UUID courseId, String reason) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);
		if (reason == null || reason.isBlank()) {
			throw new ConflictException("A reason is required to manually enroll a student");
		}

		CheckoutAmount checkout = courseLookupApi.getResolvedCheckoutAmount(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!courseLookupApi.isPublished(courseId)) {
			throw new ConflictException("Course is not available for enrollment");
		}
		if (checkout.requiresManualQuote()) {
			// CUSTOM-priced courses need a manually quoted amount, out of
			// scope for this action (mirrors OrderService#resolveAmount's
			// own "customAmount required" rejection - no equivalent input
			// exists on this staff action yet).
			throw new ConflictException(
					"This course requires a manually quoted amount and cannot be manually enrolled this way");
		}
		if (enrollmentAccessApi.resolveAccessState(studentId, courseId).state() == EnrollmentAccessStateType.ACTIVE) {
			throw new ConflictException("This student is already enrolled in this course");
		}

		StudentOrder order = new StudentOrder(tenantContext.getTenantId(), studentId, courseId, checkout.amount(),
				checkout.currency(), checkout.billingPeriodId());
		order = studentOrderRepository.save(order);
		order.markPending();

		Payment payment = new Payment(order.getTenantId(), order.getId(), order.getAmount(), order.getCurrency());
		payment = paymentRepository.save(payment);
		payment.assignGatewayReference("STAFF_GRANTED-" + payment.getId());
		payment.recordStaffGrantReason(reason);
		payment.confirm(Instant.now());
		payment = paymentRepository.save(payment);
		// Same LedgerEntryApi#recordPaymentConfirmed call every other
		// CONFIRMED-payment path uses (PaymentConfirmationService,
		// OrderService#activateFreeCheckout) - never a bespoke ledger-write
		// code path. Per .claude/rules/payments.md §2, a "paid" state with no
		// ledger row is a data-integrity bug (mirrors the exact gap
		// ADR-015 closed for the FREE-course path).
		ledgerEntryApi.recordPaymentConfirmed(order.getId(), payment.getId(), payment.getAmount());

		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		try {
			enrollmentActivationApi.fromApprovedManualEvidence(payment.getId(), order.getId(), studentId, courseId);
		}
		catch (IllegalStateException ex) {
			// Structurally near-unreachable (the payment this call chain just
			// confirmed is re-verified by fromApprovedManualEvidence itself)
			// - see EnrollmentActivationApi's existing precedent for this
			// exact "log, don't roll back a real confirmed payment" handling.
			org.slf4j.LoggerFactory.getLogger(ManualEnrollmentService.class)
				.atWarn()
				.setMessage("enrollment.manual_grant_activation_refused")
				.addKeyValue("actor", actorId)
				.addKeyValue("tenantId", payment.getTenantId())
				.addKeyValue("paymentId", payment.getId())
				.addKeyValue("orderId", order.getId())
				.addKeyValue("studentId", studentId)
				.addKeyValue("courseId", courseId)
				.addKeyValue("reason", ex.getMessage())
				.log();
		}

		auditLogApi.record(new AuditLogEntry(actorId, "enrollment.manually_granted", "payment", payment.getId(),
				reason, java.util.Map.of("studentId", studentId.toString(), "courseId", courseId.toString())));
		// Second audit row, same transaction, same action - targets the
		// student's own profile (rather than the payment row above) so this
		// staff-granted enrollment is discoverable via StudentService#listActivity
		// / GET /students/{id}/activity, per Wave 3 fix-pass (security review,
		// "enroll/revoke audit entries invisible on the student's own Activity
		// tab"). The payment-targeted row above is untouched - both are kept,
		// each serving its own traceability purpose; audit rows are append-only,
		// so this is an additional fact recorded, not a correction of the first.
		//
		// studentId here is the opaque cross-domain studentId (tenant_user.id,
		// per StudentLookupApi's own javadoc) - NOT the student_profile id
		// that GET /students/{id}/activity's {id} path segment and
		// StudentService#listActivity's targetId actually filter by. Resolve
		// the real student_profile id via StudentLookupApi (batch-shaped,
		// mirrors CourseRosterService's own established use of
		// getStudentSummariesByUserId) before writing this second row.
		Payment confirmedPayment = payment;
		resolveStudentProfileId(studentId).ifPresentOrElse(
				studentProfileId -> auditLogApi.record(new AuditLogEntry(actorId, "enrollment.manually_granted",
						"student_profile", studentProfileId, reason, java.util.Map.of("paymentId",
								confirmedPayment.getId().toString(), "courseId", courseId.toString()))),
				() -> org.slf4j.LoggerFactory.getLogger(ManualEnrollmentService.class)
					.atWarn()
					.setMessage("enrollment.manually_granted_student_profile_audit_row_skipped")
					.addKeyValue("actor", actorId)
					.addKeyValue("tenantId", confirmedPayment.getTenantId())
					.addKeyValue("paymentId", confirmedPayment.getId())
					.addKeyValue("studentId", studentId)
					.addKeyValue("reason", "studentId did not resolve to a student_profile row in this tenant")
					.log());

		return new ManualEnrollmentResult(order.getId(), payment.getId(), payment.getAmount(), payment.getCurrency());
	}

	/**
	 * Resolves a {@code tenant_user.id} (the opaque cross-domain {@code
	 * studentId} this class's own {@code Order}/{@code Payment} rows are keyed
	 * by) to its owning {@code student_profile.id}, scoped to the current
	 * tenant, via {@link StudentLookupApi#getStudentSummariesByUserId}
	 * (batch-shaped; called here with a single-element list, mirroring {@code
	 * CourseRosterService}'s established use of the same method). Returns
	 * empty if the id does not resolve to a real, current-tenant {@code
	 * student_profile} row - structurally near-unreachable (this method is
	 * only ever called with a studentId {@code StudentEnrollmentService} just
	 * resolved from a real, current-tenant {@code student_profile} row), but
	 * handled defensively rather than assumed, same "log, don't roll back a
	 * real state change" discipline this class already applies to {@link
	 * EnrollmentActivationApi#fromApprovedManualEvidence}'s structurally
	 * -near-unreachable failure case above.
	 */
	private Optional<UUID> resolveStudentProfileId(UUID studentId) {
		List<StudentSummary> summaries = studentLookupApi.getStudentSummariesByUserId(List.of(studentId));
		return summaries.stream().findFirst().map(StudentSummary::studentProfileId);
	}

}
