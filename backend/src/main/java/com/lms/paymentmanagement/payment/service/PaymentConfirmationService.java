package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.PaymentConfirmationApi;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRejectedEvent;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PaymentConfirmationApi} - the single atomic
 * webhook-confirm-and-activate method (plan §9 step 4). Called only from
 * {@code PaymentWebhookController}, never from any authenticated
 * user-facing endpoint.
 *
 * <h2>Why this method does NOT call {@code TenantContext#getTenantId()}</h2>
 * A payment gateway's server-to-server webhook has no subdomain and no JWT -
 * it hits one platform-level URL directly, so {@code TenantResolutionFilter}
 * never runs for this request and {@link com.lms.common.tenant.TenantContext}
 * is never populated the normal way. {@link
 * PaymentRepository#findByGatewayReferenceAcrossTenants(String)} is the ONE
 * repository method in this module that is not tenant-scoped by the base
 * class, used deliberately here (and ONLY here) to locate the {@link
 * Payment} row by its gateway reference alone. The found row's OWN {@code
 * tenantId} field - set correctly when the row was created during the
 * earlier, authenticated payment-initiation request - is then the trusted
 * tenant for the rest of this call; it is explicitly {@code set} on {@link
 * TenantContextHolder} in a {@code try} block (mirroring {@code
 * TenantResolutionFilter}'s own set-in-try/clear-in-finally shape, just done
 * here at the service layer since there is no HTTP-layer signal to key it
 * off for this one call path) and {@code clear}ed in a {@code finally}
 * block, so every subsequent tenant-scoped write in this method (the
 * {@code Payment} save, the {@code LedgerEntryApi} call, the {@code
 * EnrollmentActivationApi} call) goes through the normal tenant-scoped path.
 *
 * <p><b>Do not "fix" this into a plain {@code tenantContext.getTenantId()}
 * call</b> - there is no tenant context to read at the start of this method,
 * and any tenant id present in the webhook request body itself (see {@code
 * PaymentWebhookPayload}, which structurally has no such field) must NEVER
 * reach {@link TenantContextHolder#set(java.util.UUID)} - only this method's
 * own DB lookup result may.
 */
@Service
public class PaymentConfirmationService implements PaymentConfirmationApi {

	private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);

	/**
	 * Interim structured-audit-log actor identity for this webhook-driven
	 * path (plan/{@code .claude/rules/security.md}'s mandatory-audit
	 * requirement for payment approvals/rejections), distinct from any human
	 * reviewer id - see {@link com.lms.paymentmanagement.api.PaymentConfirmedEvent}'s
	 * javadoc for why this path's actor must never be a null/absent value
	 * that could be misread as "unknown human," nor a real user id (there is
	 * none - this transition is verified-integration/system-driven, never a
	 * human judgment call). {@code audit-log-management} does not exist yet
	 * (plan §6) - this SLF4J line is an interim measure only, not a
	 * substitute for that module's eventual durable audit trail.
	 */
	private static final String WEBHOOK_SYSTEM_ACTOR = "SYSTEM:payment-gateway-webhook";

	private final PaymentRepository paymentRepository;

	private final StudentOrderRepository studentOrderRepository;

	private final LedgerEntryApi ledgerEntryApi;

	private final EnrollmentActivationApi enrollmentActivationApi;

	private final ApplicationEventPublisher eventPublisher;

	public PaymentConfirmationService(PaymentRepository paymentRepository,
			StudentOrderRepository studentOrderRepository, LedgerEntryApi ledgerEntryApi,
			EnrollmentActivationApi enrollmentActivationApi, ApplicationEventPublisher eventPublisher) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
		this.ledgerEntryApi = ledgerEntryApi;
		this.enrollmentActivationApi = enrollmentActivationApi;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public void confirmByGatewayReference(String gatewayReference, boolean confirmed) {
		Payment payment = paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(gatewayReference)
			.orElseThrow(() -> new NotFoundException("No payment found for this gateway reference"));
		try {
			TenantContextHolder.set(payment.getTenantId());

			if (payment.getStatus() != PaymentStatus.PENDING) {
				// Idempotent no-op: a retried/duplicate webhook delivery for
				// an already-terminal payment must never re-run the
				// ledger/enrollment side effects a second time (plan §5/§9
				// mandatory idempotency requirement).
				return;
			}

			StudentOrder order = studentOrderRepository.findById(payment.getOrderId())
				.orElseThrow(() -> new NotFoundException("Order not found for this payment"));
			PaymentStatus previousStatus = payment.getStatus();

			if (confirmed) {
				Instant confirmedAt = Instant.now();
				payment.confirm(confirmedAt);
				paymentRepository.save(payment);
				ledgerEntryApi.recordPaymentConfirmed(order.getId(), payment.getId(), payment.getAmount());
				// MVP-012/ADR-013/M2: EnrollmentActivationApi's consolidated
				// activateOrReactivateFromConfirmedPayment owns the
				// resolve-access-state-and-branch decision that used to be
				// duplicated here (NEVER_ENROLLED -> activate, else ->
				// reactivate - see its javadoc). Safe against the
				// payment-status idempotency guard above (this branch only
				// ever runs once per payment, since a retried webhook for an
				// already-CONFIRMED payment returns early before reaching
				// here).
				try {
					enrollmentActivationApi.activateOrReactivateFromConfirmedPayment(payment.getId(), order.getId(),
							order.getStudentId(), order.getCourseId());
				}
				catch (IllegalStateException ex) {
					// Bug fix (MVP-012 review): a refusal from either branch (no
					// current enrollment / no APPROVED reactivation request
					// linked to THIS order for the reactivation branch - see
					// EnrollmentActivationApi#activateOrReactivateFromConfirmedPayment's
					// javadoc) must NOT roll back this payment's own CONFIRMED
					// transition or its ledger entry, per plan §13: "Activation
					// refused, payment stays CONFIRMED, no enrollment change -
					// logged as an ops-visible inconsistency, not silently
					// swallowed." Deliberately catches ONLY IllegalStateException
					// from THIS specific call - never widened to swallow an
					// unrelated failure.
					log.atWarn()
						.setMessage("enrollment.reactivation_refused")
						.addKeyValue("actor", WEBHOOK_SYSTEM_ACTOR)
						.addKeyValue("tenantId", payment.getTenantId())
						.addKeyValue("paymentId", payment.getId())
						.addKeyValue("orderId", order.getId())
						.addKeyValue("studentId", order.getStudentId())
						.addKeyValue("courseId", order.getCourseId())
						.addKeyValue("reason", ex.getMessage())
						.log();
				}
				eventPublisher.publishEvent(new PaymentConfirmedEvent(payment.getTenantId(), payment.getId(),
						order.getId(), previousStatus, PaymentStatus.CONFIRMED, confirmedAt));
				log.atInfo()
					.setMessage("payment.confirmed")
					.addKeyValue("actor", WEBHOOK_SYSTEM_ACTOR)
					.addKeyValue("tenantId", payment.getTenantId())
					.addKeyValue("paymentId", payment.getId())
					.addKeyValue("orderId", order.getId())
					.addKeyValue("statusBefore", previousStatus)
					.addKeyValue("statusAfter", PaymentStatus.CONFIRMED)
					.log();
			}
			else {
				payment.reject();
				paymentRepository.save(payment);
				eventPublisher.publishEvent(new PaymentRejectedEvent(payment.getTenantId(), payment.getId(),
						order.getId(), previousStatus, PaymentStatus.REJECTED, Instant.now()));
				log.atInfo()
					.setMessage("payment.rejected")
					.addKeyValue("actor", WEBHOOK_SYSTEM_ACTOR)
					.addKeyValue("tenantId", payment.getTenantId())
					.addKeyValue("paymentId", payment.getId())
					.addKeyValue("orderId", order.getId())
					.addKeyValue("statusBefore", previousStatus)
					.addKeyValue("statusAfter", PaymentStatus.REJECTED)
					.log();
			}
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
