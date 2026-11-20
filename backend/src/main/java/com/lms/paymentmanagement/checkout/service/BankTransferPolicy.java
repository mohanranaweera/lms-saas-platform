package com.lms.paymentmanagement.checkout.service;

import com.lms.common.error.BankTransferDisabledException;
import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.tenantmanagement.api.TenantConfigApi;
import org.springframework.stereotype.Service;

/**
 * SET-PAY-01: whether a student may pay the current tenant by bank transfer
 * (payment slip upload). Allowed when the tenant's {@code
 * PAYMENT.bank_transfer_enabled} switch is on, OR when online payment is not
 * available - so switching bank transfer off can never leave students with no
 * way to pay (PayHere not set up yet, switched off later by the tenant or the
 * platform, or not checkout-ready).
 *
 * <p>Applies to student self-service only. Staff-recorded manual payments
 * (ADR-030), slips already submitted and enrollment (ADR-028) never depend on
 * it. {@code CheckoutPaymentOptionsController} (integration-management) applies
 * the same two inputs for its {@code bankSlip} flag.
 */
@Service
public class BankTransferPolicy {

	static final String DISABLED_MESSAGE = "Bank transfer is not accepted by this institute. Please pay online.";

	private final TenantConfigApi tenantConfigApi;

	private final PaymentGatewayAccountApi paymentGatewayAccountApi;

	private final TenantContext tenantContext;

	public BankTransferPolicy(TenantConfigApi tenantConfigApi, PaymentGatewayAccountApi paymentGatewayAccountApi,
			TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.paymentGatewayAccountApi = paymentGatewayAccountApi;
		this.tenantContext = tenantContext;
	}

	public boolean isSlipAllowed() {
		return tenantConfigApi.isBankTransferEnabled(tenantContext.getTenantId())
				|| !paymentGatewayAccountApi.isOnlinePaymentAvailable();
	}

	/** Throws {@code 409 BANK_TRANSFER_DISABLED} when {@link #isSlipAllowed()} is false. */
	public void requireSlipAllowed() {
		if (!isSlipAllowed()) {
			throw new BankTransferDisabledException(DISABLED_MESSAGE);
		}
	}

}
