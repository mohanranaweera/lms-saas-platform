package com.lms.paymentmanagement.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.BankTransferDisabledException;
import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

/**
 * SET-PAY-01: slip allowed = tenant switch on OR online payment unavailable,
 * so switching bank transfer off never leaves students without a way to pay.
 */
class BankTransferPolicyTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@ParameterizedTest(name = "switch={0}, online={1} -> slip allowed={2}")
	@CsvSource({ "true, true, true", "true, false, true", "false, false, true", "false, true, false" })
	void slipAllowedFollowsTheSwitchUnlessOnlinePaymentIsUnavailable(boolean switchOn, boolean onlineAvailable,
			boolean expected) {
		BankTransferPolicy policy = policy(switchOn, onlineAvailable);

		assertThat(policy.isSlipAllowed()).isEqualTo(expected);
		if (expected) {
			assertThatCode(policy::requireSlipAllowed).doesNotThrowAnyException();
		}
		else {
			assertThatThrownBy(policy::requireSlipAllowed).isInstanceOfSatisfying(BankTransferDisabledException.class,
					ex -> {
						assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
						assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCodes.BANK_TRANSFER_DISABLED);
					});
		}
	}

	private static BankTransferPolicy policy(boolean switchOn, boolean onlineAvailable) {
		TenantConfigApi config = mock(TenantConfigApi.class);
		PaymentGatewayAccountApi gateway = mock(PaymentGatewayAccountApi.class);
		TenantContext tenantContext = mock(TenantContext.class);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(config.isBankTransferEnabled(TENANT_ID)).thenReturn(switchOn);
		when(gateway.isOnlinePaymentAvailable()).thenReturn(onlineAvailable);
		return new BankTransferPolicy(config, gateway, tenantContext);
	}

}
