package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.refund.domain.PaymentRefund;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates PAY-4's refund mechanism. Per {@code .claude/rules/payments.md}
 * §8, {@link PermissionCheckService#requirePermission}'s {@code
 * DomainArea#PAYMENTS_SLIPS}/{@code APPROVE} check is a coarse category
 * grant only - it never by itself justifies the mutation; this service
 * independently re-verifies the payment's terminal state and the
 * cross-row refundable-remainder invariant regardless of what that check
 * returns, and creates ONLY new rows ({@code payment_refund}, a reversing
 * {@code ledger_entry}) - {@code payment.status} is never written here (see
 * {@code Payment}'s javadoc for why {@code REFUNDED} is intentionally
 * unreachable).
 */
@Service
public class RefundService {

	private static final Logger log = LoggerFactory.getLogger(RefundService.class);

	private final PaymentQueryService paymentQueryService;

	private final PaymentRefundRepository paymentRefundRepository;

	private final LedgerEntryApi ledgerEntryApi;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	private final ApplicationEventPublisher eventPublisher;

	public RefundService(PaymentQueryService paymentQueryService, PaymentRefundRepository paymentRefundRepository,
			LedgerEntryApi ledgerEntryApi, PermissionCheckService permissionCheckService, TenantContext tenantContext,
			ApplicationEventPublisher eventPublisher) {
		this.paymentQueryService = paymentQueryService;
		this.paymentRefundRepository = paymentRefundRepository;
		this.ledgerEntryApi = ledgerEntryApi;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public PaymentRefundView processRefund(UUID paymentId, BigDecimal amount, String reason) {
		return processRefund(paymentId, amount, reason, null);
	}

	/**
	 * @param idempotencyKey optional client-supplied dedup key (V20). When
	 * present and a {@code payment_refund} row already exists for {@code
	 * (tenantId, paymentId, idempotencyKey)}, this is treated as a replay of
	 * that same request - the existing row's view is returned unchanged and
	 * neither the refundable-remainder check nor any new row/ledger entry is
	 * (re)created. {@code null} behaves exactly as {@link
	 * #processRefund(UUID, BigDecimal, String)} always has.
	 */
	@Transactional
	public PaymentRefundView processRefund(UUID paymentId, BigDecimal amount, String reason, UUID idempotencyKey) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);

		// Tenant-scoped, PESSIMISTIC_WRITE-locked load (404 for a
		// cross-tenant/nonexistent id) - not the owner-or-staff-VIEW guard (a
		// refund is never student-reachable at all, gated above by the
		// APPROVE-only check), so load the payment directly rather than via
		// the owner-checked path. The row lock is held for the rest of this
		// transaction, serializing this refund's check-then-insert against
		// any concurrent refund attempt on the same payment - without it,
		// two simultaneous refunds could each individually pass the
		// refundable-remainder check below and together over-refund.
		//
		// The idempotency-key dedup lookup below MUST run after this lock is
		// acquired, not before: two genuinely concurrent requests carrying
		// the same key would otherwise both miss a pre-lock dedup check,
		// then serialize on the lock, and the second insert would throw an
		// unhandled DataIntegrityViolationException against V20's unique
		// index instead of returning the first request's row as a replay.
		// Locking first mirrors PaymentConfirmationService's
		// lock-then-check-idempotency shape on the webhook path.
		Payment payment = paymentQueryService.loadPaymentForRefundUpdate(paymentId, tenantContext.getTenantId());

		if (idempotencyKey != null) {
			Optional<PaymentRefund> existing = paymentRefundRepository
				.findByOriginalPaymentIdAndIdempotencyKey(paymentId, idempotencyKey);
			if (existing.isPresent()) {
				return toView(existing.get());
			}
		}

		if (payment.getStatus() != PaymentStatus.CONFIRMED) {
			throw new ConflictException("Only a CONFIRMED payment can be refunded");
		}
		if (amount == null || amount.signum() <= 0) {
			throw new ConflictException("Refund amount must be positive");
		}

		BigDecimal alreadyRefunded = paymentRefundRepository.findAllByOriginalPaymentId(paymentId)
			.stream()
			.map(PaymentRefund::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal refundableRemainder = payment.getAmount().subtract(alreadyRefunded);
		if (amount.compareTo(refundableRemainder) > 0) {
			throw new ConflictException("Refund amount exceeds the refundable remainder");
		}

		PaymentRefund refund = new PaymentRefund(tenantContext.getTenantId(), paymentId, amount, reason,
				idempotencyKey);
		refund = paymentRefundRepository.save(refund);

		ledgerEntryApi.recordRefund(payment.getOrderId(), paymentId, amount);

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		eventPublisher.publishEvent(new PaymentRefundedEvent(tenantContext.getTenantId(), paymentId, refund.getId(),
				principal.userId(), amount, reason, Instant.now()));
		log.atInfo()
			.setMessage("payment.refunded")
			.addKeyValue("actor", principal.userId())
			.addKeyValue("tenantId", tenantContext.getTenantId())
			.addKeyValue("paymentId", paymentId)
			.addKeyValue("orderId", payment.getOrderId())
			.addKeyValue("refundId", refund.getId())
			.addKeyValue("refundAmount", amount)
			.addKeyValue("statusBefore", PaymentStatus.CONFIRMED)
			.addKeyValue("statusAfter", PaymentStatus.CONFIRMED)
			.log();

		return toView(refund);
	}

	@Transactional(readOnly = true)
	public List<PaymentRefundView> listRefunds(UUID paymentId) {
		// Refund listing follows the same owner-or-staff-VIEW rule as
		// payment read (plan §10), unlike refund *creation* above.
		paymentQueryService.loadPaymentForCaller(paymentId);
		return paymentRefundRepository.findAllByOriginalPaymentId(paymentId)
			.stream()
			.map(RefundService::toView)
			.toList();
	}

	private static PaymentRefundView toView(PaymentRefund refund) {
		return new PaymentRefundView(refund.getId(), refund.getOriginalPaymentId(), refund.getAmount(),
				refund.getReason(), refund.getCreatedAt());
	}

}
