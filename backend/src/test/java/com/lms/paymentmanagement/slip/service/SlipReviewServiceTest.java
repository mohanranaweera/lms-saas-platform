package com.lms.paymentmanagement.slip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.slip.domain.FlagType;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link SlipReviewService} (MVP-011/SLIP-3/
 * SLIP-4). Covers the two properties plan §18/§21 call "the sharpest
 * requirement in this module": (1) a reasonless override against a flagged
 * slip is rejected BEFORE any repository/audit-api interaction beyond the
 * flag-existence read itself, and (2) the {@code UNDER_REVIEW -> APPROVED|
 * REJECTED} state machine is one-directional - illegal from {@code
 * SUBMITTED}/{@code APPROVED}/{@code REJECTED}. The real
 * transactional/atomic-activation/audit-row-content behavior is covered
 * end-to-end by the Testcontainers integration tests in {@code
 * com.lms.paymentmanagement}.
 */
@ExtendWith(MockitoExtension.class)
class SlipReviewServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private PaymentSlipRepository paymentSlipRepository;

	@Mock
	private PaymentSlipFlagRepository paymentSlipFlagRepository;

	@Mock
	private StudentOrderRepository studentOrderRepository;

	@Mock
	private ObjectStorageApi slipStorageApi;

	@Mock
	private PaymentDomainAccessGuard accessGuard;

	@Mock
	private EnrollmentActivationApi enrollmentActivationApi;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private TenantContext tenantContext;

	private SlipReviewService slipReviewService;

	@BeforeEach
	void setUp() {
		slipReviewService = new SlipReviewService(paymentSlipRepository, paymentSlipFlagRepository,
				studentOrderRepository, slipStorageApi, accessGuard, enrollmentActivationApi, auditLogApi,
				permissionCheckService, userProvisioningApi, tenantContext);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	// ------------------------------------------------------------------
	// Override-reason presence validation - rejected BEFORE any locked
	// load/state change/audit write, with only the flag-existence read
	// having happened.
	// ------------------------------------------------------------------

	@Test
	void approveWithActiveFlagsAndANullOverrideReasonIsRejectedWithZeroDownstreamInteractions() {
		UUID slipId = UUID.randomUUID();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of(flagFixture(slipId)));

		assertThatThrownBy(() -> slipReviewService.approve(slipId, null)).isInstanceOf(ConflictException.class);

		verifyNoInteractions(studentOrderRepository, enrollmentActivationApi, auditLogApi);
		// The unlocked idempotent-replay peek (paymentSlipRepository.findById)
		// still runs before this guard - see the class javadoc - but the
		// locked load/save must never be reached for a genuine first-time
		// reasonless-override attempt.
		verify(paymentSlipRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
		verify(paymentSlipRepository, never()).save(any());
	}

	@Test
	void approveWithActiveFlagsAndAnEmptyOverrideReasonIsRejectedWithZeroDownstreamInteractions() {
		UUID slipId = UUID.randomUUID();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of(flagFixture(slipId)));

		assertThatThrownBy(() -> slipReviewService.approve(slipId, "")).isInstanceOf(ConflictException.class);

		verifyNoInteractions(studentOrderRepository, enrollmentActivationApi, auditLogApi);
		verify(paymentSlipRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
		verify(paymentSlipRepository, never()).save(any());
	}

	@Test
	void approveWithActiveFlagsAndAWhitespaceOnlyOverrideReasonIsRejectedWithZeroDownstreamInteractions() {
		UUID slipId = UUID.randomUUID();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of(flagFixture(slipId)));

		assertThatThrownBy(() -> slipReviewService.approve(slipId, "   ")).isInstanceOf(ConflictException.class);

		verifyNoInteractions(studentOrderRepository, enrollmentActivationApi, auditLogApi);
		verify(paymentSlipRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
		verify(paymentSlipRepository, never()).save(any());
	}

	@Test
	void approveWithNoFlagsAndNoOverrideReasonNeverThrowsTheOverrideConflict() {
		UUID slipId = UUID.randomUUID();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		PaymentSlip slip = underReviewSlip();
		when(paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, TENANT_ID)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.save(any(PaymentSlip.class))).thenAnswer(inv -> inv.getArgument(0));
		when(studentOrderRepository.findById(slip.getOrderId())).thenReturn(Optional.of(orderFixture(slip)));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "FINANCE_STAFF",
				UUID.randomUUID()));

		PaymentSlipView view = slipReviewService.approve(slipId, null);

		assertThat(view.status().name()).isEqualTo("APPROVED");
		verify(enrollmentActivationApi).activateOrReactivateFromApprovedSlip(any(), any(), any(), any());
		verify(auditLogApi).record(any());
	}

	// ------------------------------------------------------------------
	// State-machine transition validation: approve/reject only legal from
	// UNDER_REVIEW.
	// ------------------------------------------------------------------

	/**
	 * Bug fix (MVP-012 review): a reactivation refusal ({@link
	 * EnrollmentActivationApi#reactivateFromApprovedSlip} throwing {@link
	 * IllegalStateException}) must be caught and must NOT prevent this
	 * slip's own {@code APPROVED} transition or its audit log entry - mirrors
	 * {@code PaymentConfirmationServiceTest}'s identical coverage for the
	 * payment path.
	 */
	@Test
	void approveCatchesAReactivationRefusalAndStillCompletesTheApproval() {
		UUID slipId = UUID.randomUUID();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		PaymentSlip slip = underReviewSlip();
		when(paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, TENANT_ID)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.save(any(PaymentSlip.class))).thenAnswer(inv -> inv.getArgument(0));
		StudentOrder order = orderFixture(slip);
		when(studentOrderRepository.findById(slip.getOrderId())).thenReturn(Optional.of(order));
		doThrow(new IllegalStateException("no APPROVED reactivation request linked to order " + order.getId()))
			.when(enrollmentActivationApi)
			.activateOrReactivateFromApprovedSlip(any(), any(), any(), any());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "FINANCE_STAFF",
				UUID.randomUUID()));

		PaymentSlipView view = slipReviewService.approve(slipId, null);

		assertThat(view.status().name()).isEqualTo("APPROVED");
		verify(enrollmentActivationApi).activateOrReactivateFromApprovedSlip(slip.getId(), order.getId(),
				slip.getStudentId(), order.getCourseId());
		verify(auditLogApi).record(any());
	}

	@Test
	void approveFromSubmittedIsRejected() {
		assertApproveRejectedFrom(freshSlip());
	}

	@Test
	void approveFromAlreadyApprovedIsAnIdempotentNoOp() {
		PaymentSlip slip = approvedSlip();
		UUID slipId = slip.getId();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, TENANT_ID)).thenReturn(Optional.of(slip));

		PaymentSlipView view = slipReviewService.approve(slipId, null);

		assertThat(view.status().name()).isEqualTo("APPROVED");
		// studentOrderRepository IS legitimately read here - the idempotent
		// no-op response is still enriched with orderAmount/orderCurrency
		// (a harmless read), it just never re-triggers activation/audit.
		verifyNoInteractions(enrollmentActivationApi, auditLogApi);
		verify(paymentSlipRepository, never()).save(any());
	}

	/**
	 * CRITICAL regression coverage (ordering bug fix): {@code
	 * payment_slip_flag} rows are append-only and never cleared, so a slip
	 * originally approved via override permanently carries {@code hasFlags ==
	 * true}. A legitimate no-reason retry against that already-APPROVED slip
	 * must still succeed as an idempotent no-op - it must NOT hit the
	 * reasonless-override guard (which would incorrectly throw 409) just
	 * because the surviving flags are still there.
	 */
	@Test
	void approveFromAlreadyApprovedWithSurvivingFlagsAndNoOverrideReasonIsStillAnIdempotentNoOp() {
		PaymentSlip slip = approvedSlip();
		UUID slipId = slip.getId();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of(flagFixture(slipId)));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(paymentSlipRepository.findStatusByIdAndTenantId(slipId, TENANT_ID))
			.thenReturn(Optional.of(slip.getStatus()));
		when(paymentSlipRepository.findById(slipId)).thenReturn(Optional.of(slip));

		PaymentSlipView view = slipReviewService.approve(slipId, null);

		assertThat(view.status().name()).isEqualTo("APPROVED");
		// studentOrderRepository IS legitimately read here - see the sibling
		// no-flags idempotency test's comment above.
		verifyNoInteractions(enrollmentActivationApi, auditLogApi);
		verify(paymentSlipRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
		verify(paymentSlipRepository, never()).save(any());
	}

	@Test
	void approveFromAlreadyRejectedIsRejected() {
		assertApproveRejectedFrom(rejectedSlip());
	}

	@Test
	void rejectFromSubmittedIsRejected() {
		assertRejectRejectedFrom(freshSlip());
	}

	@Test
	void rejectFromAlreadyApprovedIsRejected() {
		assertRejectRejectedFrom(approvedSlip());
	}

	@Test
	void rejectFromAlreadyRejectedIsRejected() {
		assertRejectRejectedFrom(rejectedSlip());
	}

	@Test
	void rejectWithABlankReasonIsRejectedBeforeAnyLoad() {
		UUID slipId = UUID.randomUUID();

		assertThatThrownBy(() -> slipReviewService.reject(slipId, "   ")).isInstanceOf(ConflictException.class);

		verifyNoInteractions(paymentSlipRepository, auditLogApi);
	}

	@Test
	void rejectWithANullReasonIsRejectedBeforeAnyLoad() {
		UUID slipId = UUID.randomUUID();

		assertThatThrownBy(() -> slipReviewService.reject(slipId, null)).isInstanceOf(ConflictException.class);

		verifyNoInteractions(paymentSlipRepository, auditLogApi);
	}

	@Test
	void rejectFromUnderReviewSucceedsAndWritesAMinimumAuditRow() {
		UUID slipId = UUID.randomUUID();
		PaymentSlip slip = underReviewSlip();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, TENANT_ID)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.save(any(PaymentSlip.class))).thenAnswer(inv -> inv.getArgument(0));
		when(paymentSlipFlagRepository.findAllBySlipId(any())).thenReturn(List.of());
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "FINANCE_STAFF", UUID.randomUUID()));

		PaymentSlipView view = slipReviewService.reject(slipId, "Illegible reference number");

		assertThat(view.status().name()).isEqualTo("REJECTED");
		verify(auditLogApi).record(any());
		verifyNoInteractions(enrollmentActivationApi);
	}

	// ------------------------------------------------------------------
	// Permission-check denial (coarse gate) must never reach a
	// repository/audit-api interaction.
	// ------------------------------------------------------------------

	@Test
	void approveWhenThePermissionCheckDeniesNeverTouchesAnyRepositoryOrAuditApi() {
		UUID slipId = UUID.randomUUID();
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);

		assertThatThrownBy(() -> slipReviewService.approve(slipId, null)).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(paymentSlipFlagRepository, paymentSlipRepository, studentOrderRepository,
				enrollmentActivationApi, auditLogApi);
	}

	@Test
	void rejectWhenThePermissionCheckDeniesNeverTouchesAnyRepositoryOrAuditApi() {
		UUID slipId = UUID.randomUUID();
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);

		assertThatThrownBy(() -> slipReviewService.reject(slipId, "A reason"))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(paymentSlipRepository, auditLogApi);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private void assertApproveRejectedFrom(PaymentSlip slip) {
		UUID slipId = slip.getId();
		when(paymentSlipFlagRepository.findAllBySlipId(slipId)).thenReturn(List.of());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, TENANT_ID)).thenReturn(Optional.of(slip));

		assertThatThrownBy(() -> slipReviewService.approve(slipId, null)).isInstanceOf(ConflictException.class);

		verifyNoInteractions(studentOrderRepository, enrollmentActivationApi, auditLogApi);
		verify(paymentSlipRepository, never()).save(any());
	}

	private void assertRejectRejectedFrom(PaymentSlip slip) {
		UUID slipId = slip.getId();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(paymentSlipRepository.findByIdAndTenantIdForUpdate(slipId, TENANT_ID)).thenReturn(Optional.of(slip));

		assertThatThrownBy(() -> slipReviewService.reject(slipId, "A reason")).isInstanceOf(ConflictException.class);

		verifyNoInteractions(auditLogApi);
		verify(paymentSlipRepository, never()).save(any());
	}

	private static PaymentSlipFlag flagFixture(UUID slipId) {
		return new PaymentSlipFlag(TENANT_ID, slipId, FlagType.DUPLICATE_REFERENCE);
	}

	private static PaymentSlip freshSlip() {
		PaymentSlip slip = new PaymentSlip(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), "storage-key", "REF-1",
				"hash-1");
		ReflectionTestUtils.setField(slip, "id", UUID.randomUUID());
		return slip;
	}

	private static PaymentSlip underReviewSlip() {
		PaymentSlip slip = freshSlip();
		slip.markUnderReview();
		return slip;
	}

	private static PaymentSlip approvedSlip() {
		PaymentSlip slip = underReviewSlip();
		slip.approve(UUID.randomUUID(), Instant.now());
		return slip;
	}

	private static PaymentSlip rejectedSlip() {
		PaymentSlip slip = underReviewSlip();
		slip.reject(UUID.randomUUID(), Instant.now());
		return slip;
	}

	private static StudentOrder orderFixture(PaymentSlip slip) {
		StudentOrder order = new StudentOrder(TENANT_ID, slip.getStudentId(), UUID.randomUUID(),
				new BigDecimal("99.99"), "USD");
		ReflectionTestUtils.setField(order, "id", slip.getOrderId());
		return order;
	}

}
