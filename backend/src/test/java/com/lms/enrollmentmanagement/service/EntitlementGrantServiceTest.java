package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi.GrantResult;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import com.lms.enrollmentmanagement.domain.ContentEntitlementScope;
import com.lms.enrollmentmanagement.repository.ContentEntitlementRepository;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KLASS-T4c: the grant path never trusts its caller - an unconfirmed payment,
 * a payment of another order or a non-approved slip grants nothing; a
 * line-less order is a no-op; an entitlement row can be revoked only once.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementGrantServiceTest {

	@Mock
	private ContentEntitlementRepository entitlementRepository;

	@Mock
	private PaymentStatusApi paymentStatusApi;

	@Mock
	private SlipStatusApi slipStatusApi;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private TenantContext tenantContext;

	private EntitlementGrantService service;

	@BeforeEach
	void setUp() {
		service = new EntitlementGrantService(entitlementRepository, paymentStatusApi, slipStatusApi, courseLookupApi,
				auditLogApi, tenantContext);
	}

	@Test
	void anUnconfirmedOrForeignPaymentIsRefusedBeforeAnyWrite() {
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		when(paymentStatusApi.isConfirmedForOrder(paymentId, orderId)).thenReturn(false);

		assertThatThrownBy(() -> service.grantForConfirmedPayment(paymentId, orderId))
			.isInstanceOf(IllegalStateException.class);
		verify(paymentStatusApi, never()).findOrderLines(any());
		verify(entitlementRepository, never()).save(any());
	}

	@Test
	void aNonApprovedSlipIsRefusedBeforeAnyWrite() {
		UUID slipId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		when(slipStatusApi.isApprovedForOrder(slipId, orderId)).thenReturn(false);

		assertThatThrownBy(() -> service.grantForApprovedSlip(slipId, orderId, UUID.randomUUID()))
			.isInstanceOf(IllegalStateException.class);
		verify(entitlementRepository, never()).save(any());
	}

	@Test
	void aLinelessOrderGrantsNothing() {
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		when(paymentStatusApi.isConfirmedForOrder(paymentId, orderId)).thenReturn(true);
		when(paymentStatusApi.findOrderLines(orderId)).thenReturn(List.of());

		assertThat(service.grantForConfirmedPayment(paymentId, orderId)).isEqualTo(GrantResult.NONE);
		verify(entitlementRepository, never()).save(any());
	}

	@Test
	void anEntitlementNeedsExactlyOneEvidenceAndIsRevokedOnlyOnce() {
		UUID course = UUID.randomUUID();
		UUID lesson = UUID.randomUUID();
		assertThatThrownBy(() -> ContentEntitlement.grant(UUID.randomUUID(), UUID.randomUUID(),
				ContentEntitlementScope.LESSON, course, null, lesson, null, null, "LESSON:" + lesson, UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), null))
			.isInstanceOf(IllegalArgumentException.class);

		ContentEntitlement entitlement = ContentEntitlement.grant(UUID.randomUUID(), UUID.randomUUID(),
				ContentEntitlementScope.LESSON, course, null, lesson, null, null, "LESSON:" + lesson, UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), null, Instant.now(), null);
		entitlement.revoke(Instant.now(), "CHARGEBACK", null);
		assertThat(entitlement.isActiveAt(Instant.now())).isFalse();
		assertThatThrownBy(() -> entitlement.revoke(Instant.now(), "EXPIRED_SUPERSEDED", null))
			.isInstanceOf(IllegalStateException.class);
	}

}
