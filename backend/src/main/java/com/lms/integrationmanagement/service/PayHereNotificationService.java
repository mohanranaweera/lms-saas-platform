package com.lms.integrationmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.domain.GatewayProvider;
import com.lms.integrationmanagement.domain.TenantPaymentGatewayAccount;
import com.lms.integrationmanagement.gateway.CredentialCipher;
import com.lms.integrationmanagement.gateway.PayHereSignatures;
import com.lms.integrationmanagement.repository.TenantPaymentGatewayAccountRepository;
import com.lms.paymentmanagement.api.GatewayPaymentSnapshot;
import com.lms.paymentmanagement.api.PaymentChargebackApi;
import com.lms.paymentmanagement.api.PaymentConfirmationApi;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Verifies and applies a PayHere {@code notify_url} callback (PAY-GW-01 /
 * ADR-017, extending ADR-011's webhook tenant-resolution carve-out).
 *
 * <p>Order of checks - nothing changes state until all pass:
 * <ol>
 * <li>{@code order_id} resolves to our Payment via its opaque gateway
 * reference (ADR-011's single named cross-tenant lookup). Tenant = that
 * payment's tenant; nothing in the payload chooses the tenant.</li>
 * <li>The payment's own collecting account is loaded inside that tenant, and
 * {@code md5sig} is verified with that account's secret.</li>
 * <li>{@code merchant_id}, amount and currency must equal the stored
 * account/payment exactly.</li>
 * </ol>
 * Then {@code status_code}: {@code 2} confirms (existing idempotent path,
 * activating enrollment), {@code -1}/{@code -2} reject a PENDING payment,
 * {@code 0} is ignored, {@code -3} records a chargeback (refund + revoke).
 * Any failed check is logged as a security event and rejected.
 */
@Service
public class PayHereNotificationService {

	private static final Logger log = LoggerFactory.getLogger(PayHereNotificationService.class);

	/** Outcome returned to the controller - REJECTED maps to HTTP 400, everything else 200. */
	public enum Outcome {
		CONFIRMED, FAILED, IGNORED, CHARGED_BACK, REJECTED
	}

	private final PaymentChargebackApi paymentChargebackApi;

	private final PaymentConfirmationApi paymentConfirmationApi;

	private final TenantPaymentGatewayAccountRepository accountRepository;

	private final CredentialCipher credentialCipher;

	public PayHereNotificationService(PaymentChargebackApi paymentChargebackApi,
			PaymentConfirmationApi paymentConfirmationApi, TenantPaymentGatewayAccountRepository accountRepository,
			CredentialCipher credentialCipher) {
		this.paymentChargebackApi = paymentChargebackApi;
		this.paymentConfirmationApi = paymentConfirmationApi;
		this.accountRepository = accountRepository;
		this.credentialCipher = credentialCipher;
	}

	public Outcome handle(Map<String, String> params) {
		String reference = params.get("order_id");
		String statusCode = params.get("status_code");
		if (!StringUtils.hasText(reference) || !StringUtils.hasText(statusCode)) {
			return reject(reference, null, "missing order_id or status_code");
		}
		Optional<GatewayPaymentSnapshot> found = paymentChargebackApi.findByGatewayReference(reference);
		if (found.isEmpty()) {
			return reject(reference, null, "unknown gateway reference");
		}
		GatewayPaymentSnapshot payment = found.get();
		if (payment.gatewayAccountId() == null) {
			return reject(reference, payment, "payment was not collected through a tenant gateway account");
		}

		Optional<TenantPaymentGatewayAccount> account;
		try {
			TenantContextHolder.set(payment.tenantId());
			account = accountRepository.findById(payment.gatewayAccountId())
				.filter(candidate -> candidate.getProvider() == GatewayProvider.PAYHERE);
		}
		finally {
			TenantContextHolder.clear();
		}
		if (account.isEmpty()) {
			return reject(reference, payment, "collecting gateway account not found");
		}

		String secret = credentialCipher.decrypt(account.get().getMerchantSecretCiphertext());
		String expected = PayHereSignatures.notificationSignature(nullToEmpty(params.get("merchant_id")), reference,
				nullToEmpty(params.get("payhere_amount")), nullToEmpty(params.get("payhere_currency")), statusCode,
				secret);
		if (!PayHereSignatures.matches(expected, params.get("md5sig"))) {
			return reject(reference, payment, "invalid md5sig");
		}
		if (!account.get().getMerchantId().equals(params.get("merchant_id"))) {
			return reject(reference, payment, "merchant_id does not match the collecting account");
		}
		if (!amountMatches(params.get("payhere_amount"), payment.amount())
				|| !payment.currency().equals(params.get("payhere_currency"))) {
			return reject(reference, payment, "amount or currency does not match the payment");
		}

		switch (statusCode) {
			case "2" -> {
				paymentConfirmationApi.confirmByGatewayReference(reference, true);
				return Outcome.CONFIRMED;
			}
			case "-1", "-2" -> {
				if ("PENDING".equals(payment.status())) {
					paymentConfirmationApi.confirmByGatewayReference(reference, false);
					return Outcome.FAILED;
				}
				return Outcome.IGNORED;
			}
			case "0" -> {
				return Outcome.IGNORED;
			}
			case "-3" -> {
				String externalReference = params.get("payment_id");
				if (!StringUtils.hasText(externalReference)) {
					return reject(reference, payment, "chargeback without payment_id");
				}
				try {
					paymentChargebackApi.recordChargeback(reference, "PAYHERE:" + externalReference.trim());
					return Outcome.CHARGED_BACK;
				}
				catch (ConflictException ex) {
					return reject(reference, payment, "chargeback for a payment that is not CONFIRMED");
				}
			}
			default -> {
				return reject(reference, payment, "unknown status_code");
			}
		}
	}

	private static boolean amountMatches(String received, BigDecimal stored) {
		if (!StringUtils.hasText(received)) {
			return false;
		}
		try {
			return new BigDecimal(received.trim()).compareTo(stored) == 0;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private static String nullToEmpty(String value) {
		return (value == null) ? "" : value;
	}

	private static Outcome reject(String reference, GatewayPaymentSnapshot payment, String reason) {
		log.atWarn()
			.setMessage("security.payment_notification_rejected")
			.addKeyValue("provider", GatewayProvider.PAYHERE)
			.addKeyValue("gatewayReference", reference)
			.addKeyValue("tenantId", (payment == null) ? null : payment.tenantId())
			.addKeyValue("paymentId", (payment == null) ? null : payment.paymentId())
			.addKeyValue("reason", reason)
			.log();
		return Outcome.REJECTED;
	}

}
