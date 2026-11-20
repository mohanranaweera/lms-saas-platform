package com.lms.integrationmanagement.gateway;

import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.GatewayCheckoutRequest;
import com.lms.integrationmanagement.api.PaymentGatewayApi;
import com.lms.integrationmanagement.api.PaymentGatewayInitiation;
import com.lms.integrationmanagement.config.PaymentGatewayProperties;
import com.lms.integrationmanagement.domain.GatewayProvider;
import com.lms.integrationmanagement.domain.TenantPaymentGatewayAccount;
import com.lms.integrationmanagement.repository.TenantPaymentGatewayAccountRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link PaymentGatewayApi} every other module receives (PAY-GW-01 /
 * ADR-017): routes each payment to the gateway of the tenant's own ACTIVE
 * account (PayHere). With no active account, online payment is refused with
 * a clear message - unless {@code payment.gateway.fake-fallback-enabled} is
 * set (local/test profiles only), which keeps the pre-PAY-GW-01 fake gateway
 * flow.
 *
 * <p>Tenant is always the resolved request tenant; the request's {@code
 * tenantId} must match it.
 */
@Primary
@Component
public class PaymentGatewayRouter implements PaymentGatewayApi {

	private final FakePaymentGatewayAdapter fakeAdapter;

	private final PayHereGatewayAdapter payHereAdapter;

	private final TenantPaymentGatewayAccountRepository accountRepository;

	private final PaymentGatewayProperties properties;

	private final TenantContext tenantContext;

	public PaymentGatewayRouter(FakePaymentGatewayAdapter fakeAdapter, PayHereGatewayAdapter payHereAdapter,
			TenantPaymentGatewayAccountRepository accountRepository, PaymentGatewayProperties properties,
			TenantContext tenantContext) {
		this.fakeAdapter = fakeAdapter;
		this.payHereAdapter = payHereAdapter;
		this.accountRepository = accountRepository;
		this.properties = properties;
		this.tenantContext = tenantContext;
	}

	@Override
	public PaymentGatewayInitiation initiatePayment(UUID orderId, UUID tenantId, BigDecimal amount, String currency) {
		return initiateCheckout(new GatewayCheckoutRequest(orderId, tenantId, amount, currency, null, null));
	}

	@Override
	public void validateCheckout(GatewayCheckoutRequest request) {
		if (accountRepository.findActive(GatewayProvider.PAYHERE).isPresent()) {
			payHereAdapter.validatedPayer(request);
		}
	}

	@Override
	public PaymentGatewayInitiation initiateCheckout(GatewayCheckoutRequest request) {
		if (!request.tenantId().equals(tenantContext.getTenantId())) {
			throw new IllegalStateException("Checkout tenant does not match the resolved tenant context");
		}
		Optional<TenantPaymentGatewayAccount> account = accountRepository.findActive(GatewayProvider.PAYHERE);
		if (account.isPresent()) {
			return payHereAdapter.initiate(request, account.get());
		}
		if (properties.isFakeFallbackEnabled()) {
			return fakeAdapter.initiatePayment(request.orderId(), request.tenantId(), request.amount(),
					request.currency());
		}
		throw new ConflictException(
				"Online payment is not available for this institute. Please pay by bank transfer.");
	}

	/** Signature check for the generic JSON webhook (fake gateway); PayHere notifications are verified separately. */
	@Override
	public boolean verifySignature(String rawBody, String signatureHeader) {
		return fakeAdapter.verifySignature(rawBody, signatureHeader);
	}

}
