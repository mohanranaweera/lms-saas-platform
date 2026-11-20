package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.integrationmanagement.api.GatewayCheckoutRequest;
import com.lms.integrationmanagement.api.GatewayPayer;
import com.lms.integrationmanagement.api.PaymentGatewayInitiation;
import com.lms.integrationmanagement.config.PayHereProperties;
import com.lms.integrationmanagement.config.PaymentCredentialProperties;
import com.lms.integrationmanagement.domain.GatewayMode;
import com.lms.integrationmanagement.domain.GatewayProvider;
import com.lms.integrationmanagement.domain.TenantPaymentGatewayAccount;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.usermanagement.api.TeacherStorefrontLookupApi;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * STORE-01 / ADR-037 decision 8: the PayHere return/cancel URLs use the
 * storefront origin while the order's storefront is enabled, and the tenant
 * origin otherwise. Nothing else in the signed form changes.
 */
class PayHereStorefrontReturnUrlTest {

	private static final String TEMPLATE = "https://{subdomain}.example.test";

	private final TenantLookupApi tenantLookupApi = mock(TenantLookupApi.class);

	private final TeacherStorefrontLookupApi storefrontLookupApi = mock(TeacherStorefrontLookupApi.class);

	private final CredentialCipher cipher;

	private final PayHereGatewayAdapter adapter;

	private final UUID tenantId = UUID.randomUUID();

	private final UUID orderId = UUID.randomUUID();

	PayHereStorefrontReturnUrlTest() {
		PaymentCredentialProperties credentials = new PaymentCredentialProperties();
		credentials.setEncryptionKey(
				Base64.getEncoder().encodeToString("unit-test-only-key-32-bytes-long".getBytes()));
		cipher = new CredentialCipher(credentials);
		PayHereProperties properties = new PayHereProperties();
		properties.setNotifyUrl("https://notify.example.test/api/v1/integrations/webhooks/payhere");
		properties.setReturnUrlTemplate(TEMPLATE);
		adapter = new PayHereGatewayAdapter(properties, cipher, tenantLookupApi, storefrontLookupApi);
		when(tenantLookupApi.tenantOrigin(tenantId, TEMPLATE)).thenReturn(Optional.of("https://abc.example.test"));
	}

	private TenantPaymentGatewayAccount account() {
		return new TenantPaymentGatewayAccount(tenantId, GatewayProvider.PAYHERE, GatewayMode.SANDBOX, "1210000",
				cipher.encrypt("merchant-secret"));
	}

	private GatewayCheckoutRequest request(UUID storefrontId) {
		return new GatewayCheckoutRequest(orderId, tenantId, new BigDecimal("3500.00"), "LKR", "Physics",
				new GatewayPayer("Nimal", "Silva", "n@example.test", "0771234567", "12 Temple Road", "Kandy"),
				storefrontId);
	}

	@Test
	void anEnabledStorefrontOrderReturnsToTheStorefront() {
		UUID storefrontId = UUID.randomUUID();
		when(storefrontLookupApi.enabledStorefrontOrigin(storefrontId, TEMPLATE))
			.thenReturn(Optional.of("https://alice.abc.example.test"));

		PaymentGatewayInitiation initiation = adapter.initiate(request(storefrontId), account());

		assertThat(initiation.formFields().get("return_url"))
			.isEqualTo("https://alice.abc.example.test/student/payments/awaiting-confirmation/" + orderId);
		assertThat(initiation.formFields().get("cancel_url"))
			.isEqualTo("https://alice.abc.example.test/student/payments/cancelled/" + orderId);
	}

	@Test
	void aDisabledStorefrontOrderReturnsToTheTenantSite() {
		UUID storefrontId = UUID.randomUUID();
		when(storefrontLookupApi.enabledStorefrontOrigin(storefrontId, TEMPLATE)).thenReturn(Optional.empty());

		PaymentGatewayInitiation initiation = adapter.initiate(request(storefrontId), account());

		assertThat(initiation.formFields().get("return_url"))
			.isEqualTo("https://abc.example.test/student/payments/awaiting-confirmation/" + orderId);
	}

	@Test
	void aTenantSiteOrderNeverConsultsTheStorefrontLookup() {
		PaymentGatewayInitiation initiation = adapter.initiate(request(null), account());

		assertThat(initiation.formFields().get("return_url"))
			.isEqualTo("https://abc.example.test/student/payments/awaiting-confirmation/" + orderId);
		verify(storefrontLookupApi, never()).enabledStorefrontOrigin(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

}
