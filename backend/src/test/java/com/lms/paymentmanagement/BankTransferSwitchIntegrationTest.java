package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.HttpResult;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.integrationmanagement.web.dto.CheckoutPaymentOptionsResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * SET-PAY-01 over HTTP: the tenant's {@code PAYMENT.bank_transfer_enabled}
 * switch. The test profile enables the fake gateway fallback, so online
 * payment is available here and switching bank transfer off takes effect
 * (the "online unavailable keeps slips open" branch is covered by {@code
 * BankTransferPolicyTest}).
 */
class BankTransferSwitchIntegrationTest extends SlipTestSupport {

	@Autowired
	private InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi inMemorySlipStorageApi;

	@Test
	void bankTransferIsOfferedByDefault() {
		SlipFixture fixture = seedTenantWithOrder("bt-default");

		assertThat(paymentOptions(fixture).bankSlip()).isTrue();
		assertThat(uploadSlip(fixture.host(), fixture.studentToken(), fixture.order().id(), "REF-BT-DEFAULT",
				pdfFile("slip.pdf")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void switchedOffRejectsStudentSlipUploadWith409AndWritesNothing() {
		SlipFixture fixture = seedTenantWithOrder("bt-off");
		setBankTransfer(fixture, false);
		Set<String> keysBefore = inMemorySlipStorageApi.keySet();

		HttpResult<PaymentSlipResponse> result = uploadSlip(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-BT-OFF", pdfFile("slip.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(result.getBody().error().code()).isEqualTo("BANK_TRANSFER_DISABLED");
		assertThat(paymentOptions(fixture).bankSlip()).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip WHERE order_id = ?", Long.class,
				fixture.order().id())).isZero();
		assertThat(inMemorySlipStorageApi.keySet()).isEqualTo(keysBefore);
	}

	@Test
	void switchingBackOnAllowsUploadsAgain() {
		SlipFixture fixture = seedTenantWithOrder("bt-on-again");
		setBankTransfer(fixture, false);
		setBankTransfer(fixture, true);

		assertThat(paymentOptions(fixture).bankSlip()).isTrue();
		assertThat(uploadSlip(fixture.host(), fixture.studentToken(), fixture.order().id(), "REF-BT-AGAIN",
				pdfFile("slip.pdf")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void aSlipSubmittedBeforeTheSwitchIsTurnedOffCanStillBeApproved() {
		SlipFixture fixture = seedTenantWithOrder("bt-existing");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-BT-EXISTING", pdfFile("slip.pdf"));
		setBankTransfer(fixture, false);

		HttpResult<PaymentSlipResponse> approved = approveSlip(fixture.host(), fixture.financeToken(), slip.id(),
				null);

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(approved.getBody().data().status().name()).isEqualTo("APPROVED");
	}

	@Test
	void switchChangeIsAuditedWithBeforeAndAfter() {
		SlipFixture fixture = seedTenantWithOrder("bt-audit");

		setBankTransfer(fixture, false);

		String metadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = 'tenant_config.updated' "
						+ "AND metadata::text LIKE '%bank_transfer_enabled%'",
				String.class, fixture.tenant().getId());
		assertThat(metadata).contains("false");
	}

	@Test
	void financeStaffCannotChangeTheSwitch() {
		SlipFixture fixture = seedTenantWithOrder("bt-finance");

		int status = perform(authenticated(put("/api/v1/tenant-config/{domain}", "PAYMENT")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"bank_transfer_enabled\":false}"), fixture.host(), fixture.financeToken())).getResponse()
			.getStatus();

		assertThat(status).isEqualTo(403);
		assertThat(paymentOptions(fixture).bankSlip()).isTrue();
	}

	@Test
	@Tag("cross-tenant")
	void tenantASwitchingOffNeverAffectsTenantB() {
		SlipFixture tenantA = seedTenantWithOrder("bt-cross-a");
		SlipFixture tenantB = seedTenantWithOrder("bt-cross-b");

		setBankTransfer(tenantA, false);

		assertThat(paymentOptions(tenantA).bankSlip()).isFalse();
		assertThat(paymentOptions(tenantB).bankSlip()).isTrue();
		assertThat(uploadSlip(tenantB.host(), tenantB.studentToken(), tenantB.order().id(), "REF-BT-CROSS-B",
				pdfFile("slip.pdf")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private void setBankTransfer(SlipFixture fixture, boolean enabled) {
		int status = perform(authenticated(put("/api/v1/tenant-config/{domain}", "PAYMENT")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bank_transfer_enabled", enabled))), fixture.host(),
				fixture.adminToken())).getResponse().getStatus();
		assertThat(status).isEqualTo(200);
	}

	private CheckoutPaymentOptionsResponse paymentOptions(SlipFixture fixture) {
		return parseSingle(perform(authenticated(get("/api/v1/checkout/payment-options"), fixture.host(),
				fixture.studentToken())), CheckoutPaymentOptionsResponse.class)
			.getBody()
			.data();
	}

}
