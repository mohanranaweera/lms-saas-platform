package com.lms.integrationmanagement.gateway;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.GatewayCheckoutRequest;
import com.lms.integrationmanagement.api.GatewayPayer;
import com.lms.integrationmanagement.api.PaymentGatewayInitiation;
import com.lms.integrationmanagement.config.PayHereProperties;
import com.lms.integrationmanagement.domain.GatewayMode;
import com.lms.integrationmanagement.domain.TenantPaymentGatewayAccount;
import com.lms.tenantmanagement.api.TenantLookupApi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds a signed PayHere Checkout API form for one payment, paid into the
 * tenant's own merchant account (PAY-GW-01 / ADR-017). Makes no outbound call:
 * the frontend auto-submits the returned fields to PayHere. The merchant
 * secret is decrypted only here, only to compute the hash, and never leaves
 * the backend.
 *
 * <p>{@code order_id} sent to PayHere is our opaque {@code
 * payment.gateway_reference} - the notification is resolved back to the
 * payment (and its tenant) through that, never through the payload's
 * merchant id.
 */
@Component
public class PayHereGatewayAdapter {

	static final String REFERENCE_PREFIX = "PH-";

	private static final String NOT_AVAILABLE = "Online payment is not available right now. Please pay by bank transfer.";

	private final PayHereProperties properties;

	private final CredentialCipher credentialCipher;

	private final TenantLookupApi tenantLookupApi;

	public PayHereGatewayAdapter(PayHereProperties properties, CredentialCipher credentialCipher,
			TenantLookupApi tenantLookupApi) {
		this.properties = properties;
		this.credentialCipher = credentialCipher;
		this.tenantLookupApi = tenantLookupApi;
	}

	/** Whether the platform side of PayHere (notify URL, return URLs, encryption key) is configured. */
	public boolean isOperational() {
		return StringUtils.hasText(properties.getNotifyUrl()) && StringUtils.hasText(properties.getReturnUrlTemplate())
				&& credentialCipher.isConfigured();
	}

	public PaymentGatewayInitiation initiate(GatewayCheckoutRequest request, TenantPaymentGatewayAccount account) {
		if (!StringUtils.hasText(properties.getNotifyUrl()) || !StringUtils.hasText(properties.getReturnUrlTemplate())) {
			throw new ServiceUnavailableException(NOT_AVAILABLE);
		}
		String subdomain = tenantLookupApi.findSubdomain(request.tenantId())
			.orElseThrow(() -> new ServiceUnavailableException(NOT_AVAILABLE));
		String storefront = stripTrailingSlash(properties.getReturnUrlTemplate().replace("{subdomain}", subdomain));
		GatewayPayer payer = validatedPayer(request);
		String secret = credentialCipher.decrypt(account.getMerchantSecretCiphertext());

		String reference = REFERENCE_PREFIX + UUID.randomUUID();

		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("merchant_id", account.getMerchantId());
		fields.put("return_url", storefront + "/student/payments/awaiting-confirmation/" + request.orderId());
		fields.put("cancel_url", storefront + "/student/payments/cancelled/" + request.orderId());
		fields.put("notify_url", properties.getNotifyUrl());
		fields.put("order_id", reference);
		fields.put("items", orDefault(request.itemDescription(), "Course enrollment"));
		fields.put("currency", request.currency());
		fields.put("amount", PayHereSignatures.formatAmount(request.amount()));
		fields.put("first_name", orDefault(payer.firstName(), "Student"));
		fields.put("last_name", orDefault(payer.lastName(), "-"));
		fields.put("email", orDefault(payer.email(), ""));
		fields.put("phone", orDefault(payer.phone(), ""));
		fields.put("address", payer.addressLine().trim());
		fields.put("city", payer.city().trim());
		fields.put("country", "Sri Lanka");
		fields.put("hash", PayHereSignatures.checkoutHash(account.getMerchantId(), reference, request.amount(),
				request.currency(), secret));

		return new PaymentGatewayInitiation(reference, null, checkoutUrl(account.getMode()), fields, account.getId(),
				"TENANT");
	}

	/**
	 * PayHere requires the payer's address and city; the student keeps them on
	 * their own profile (V58). Called before any payment row is created (via
	 * {@code PaymentGatewayApi#validateCheckout}) and again at initiation.
	 */
	public GatewayPayer validatedPayer(GatewayCheckoutRequest request) {
		GatewayPayer payer = request.payer();
		if (payer == null || !StringUtils.hasText(payer.addressLine()) || !StringUtils.hasText(payer.city())) {
			throw new FieldValidationException("Add your address and city in your profile before paying online.",
					List.of(new FieldError("billingAddress", "address and city are required for online payment")));
		}
		return payer;
	}

	private String checkoutUrl(GatewayMode mode) {
		return (mode == GatewayMode.LIVE) ? properties.getLiveCheckoutUrl() : properties.getSandboxCheckoutUrl();
	}

	private static String orDefault(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

}
