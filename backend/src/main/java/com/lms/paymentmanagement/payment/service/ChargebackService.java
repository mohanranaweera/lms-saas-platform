package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.GatewayPaymentSnapshot;
import com.lms.paymentmanagement.api.PaymentChargebackApi;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.refund.domain.PaymentRefund;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verified gateway chargebacks (PAY-GW-01 / ADR-017 owner decision 2).
 *
 * <p>Ledger rules (.claude/rules/payments.md §1/§4): the CONFIRMED payment row
 * is never modified; the reversal is a new {@code payment_refund} row
 * ({@code source = CHARGEBACK}) whose existing {@code REFUND} ledger entry
 * reverses the original {@code PAYMENT_CONFIRMED} entry - no new ledger entry
 * type. Tenant context is set from the payment row itself (ADR-011 pattern),
 * never from the notification.
 */
@Service
public class ChargebackService implements PaymentChargebackApi {

	private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);

	static final String CHARGEBACK_REASON = "Payment charged back by the gateway";

	static final String REVOKE_REASON = "PAYHERE_CHARGEBACK";

	private final PaymentRepository paymentRepository;

	private final PaymentRefundRepository paymentRefundRepository;

	private final LedgerEntryApi ledgerEntryApi;

	private final EnrollmentActivationApi enrollmentActivationApi;

	private final EntitlementGrantApi entitlementGrantApi;

	public ChargebackService(PaymentRepository paymentRepository, PaymentRefundRepository paymentRefundRepository,
			LedgerEntryApi ledgerEntryApi, EnrollmentActivationApi enrollmentActivationApi,
			EntitlementGrantApi entitlementGrantApi) {
		this.entitlementGrantApi = entitlementGrantApi;
		this.paymentRepository = paymentRepository;
		this.paymentRefundRepository = paymentRefundRepository;
		this.ledgerEntryApi = ledgerEntryApi;
		this.enrollmentActivationApi = enrollmentActivationApi;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<GatewayPaymentSnapshot> findByGatewayReference(String gatewayReference) {
		return paymentRepository.findByGatewayReferenceAcrossTenants(gatewayReference)
			.map(payment -> new GatewayPaymentSnapshot(payment.getId(), payment.getTenantId(), payment.getAmount(),
					payment.getCurrency(), payment.getStatus().name(), payment.getGatewayAccountId()));
	}

	@Override
	@Transactional
	public void recordChargeback(String gatewayReference, String externalReference) {
		Payment payment = paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(gatewayReference)
			.orElseThrow(() -> new NotFoundException("No payment found for this gateway reference"));
		try {
			TenantContextHolder.set(payment.getTenantId());
			if (paymentRefundRepository.findByExternalReference(externalReference).isPresent()) {
				return; // repeated notification - already recorded
			}
			if (payment.getStatus() != PaymentStatus.CONFIRMED) {
				throw new ConflictException("Only a CONFIRMED payment can be charged back");
			}

			BigDecimal alreadyRefunded = paymentRefundRepository.findAllByOriginalPaymentId(payment.getId())
				.stream()
				.map(PaymentRefund::getAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			BigDecimal remaining = payment.getAmount().subtract(alreadyRefunded);

			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("externalReference", externalReference);
			metadata.put("chargebackAmount", remaining.max(BigDecimal.ZERO).toPlainString());
			UUID refundId = null;
			if (remaining.signum() > 0) {
				PaymentRefund refund = paymentRefundRepository.save(PaymentRefund.chargeback(payment.getTenantId(),
						payment.getId(), remaining, CHARGEBACK_REASON, externalReference));
				ledgerEntryApi.recordRefund(payment.getOrderId(), payment.getId(), remaining);
				refundId = refund.getId();
				metadata.put("refundId", refund.getId().toString());
			}
			boolean revoked = enrollmentActivationApi.revokeActivatedByPayment(payment.getId(), REVOKE_REASON);
			metadata.put("enrollmentRevoked", revoked);
			// KLASS-T4c (D08): also revoke the content entitlements this payment
			// granted (revoke columns only). Free participation is untouched.
			int entitlementsRevoked = entitlementGrantApi.revokeForChargeback(payment.getId(), refundId);
			metadata.put("entitlementsRevoked", entitlementsRevoked);

			// No audit_log row: it requires a real user actor (V33 trigger).
			// Durable record = the CHARGEBACK refund row + REFUND ledger entry
			// + the enrollment's revoked_at/revoke_reason; plus this security
			// log - the same precedent as verified webhook confirmations.
			log.atWarn()
				.setMessage("payment.charged_back")
				.addKeyValue("actor", "SYSTEM:payment-gateway-webhook")
				.addKeyValue("details", metadata)
				.addKeyValue("tenantId", payment.getTenantId())
				.addKeyValue("paymentId", payment.getId())
				.addKeyValue("orderId", payment.getOrderId())
				.addKeyValue("chargebackAmount", remaining)
				.addKeyValue("enrollmentRevoked", revoked)
				.addKeyValue("entitlementsRevoked", entitlementsRevoked)
				.log();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
