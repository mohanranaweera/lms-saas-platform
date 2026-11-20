package com.lms.paymentmanagement.manualpayment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi.GrantResult;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.service.PermissionCheckServiceImpl;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.checkout.service.CheckoutComposerService;
import com.lms.paymentmanagement.checkout.service.CheckoutItemException;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCart;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOrder;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.QuoteLine;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService;
import com.lms.paymentmanagement.manualpayment.domain.ManualPaymentReceipt;
import com.lms.paymentmanagement.manualpayment.domain.PaymentMethodType;
import com.lms.paymentmanagement.manualpayment.repository.ManualPaymentReceiptRepository;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.ReceiptView;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.RecordCommand;
import com.lms.paymentmanagement.order.domain.OrderStatus;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import com.lms.paymentmanagement.order.repository.StudentOrderCourseChargeRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR-030 (M1-M7): {@link ManualPaymentService} validation, amount rules,
 * idempotent replay and the confirmation sequence, with every collaborator
 * mocked and the REAL permission matrix. The PostgreSQL behaviour (rollback,
 * locks, triggers) is covered by the QA integration tests.
 */
@ExtendWith(MockitoExtension.class)
class ManualPaymentServiceTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID STAFF = UUID.randomUUID();

	private static final UUID PROFILE = UUID.randomUUID();

	private static final UUID STUDENT = UUID.randomUUID();

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID ORDER = UUID.randomUUID();

	@Mock
	private ManualPaymentSupport support;

	@Mock
	private ManualPaymentReceiptRepository receiptRepository;

	@Mock
	private CheckoutComposerService composerService;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private LedgerEntryApi ledgerEntryApi;

	@Mock
	private EntitlementGrantApi entitlementGrantApi;

	@Mock
	private DiscountRedemptionService discountRedemptionService;

	@Mock
	private StudentOrderLineRepository lineRepository;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private StudentLookupApi studentLookupApi;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private StudentOrderCourseChargeRepository chargeRepository;

	private ManualPaymentService service;

	@BeforeEach
	void setUp() {
		service = new ManualPaymentService(support, receiptRepository, composerService, paymentRepository,
				ledgerEntryApi, entitlementGrantApi, discountRedemptionService, lineRepository,
				new PermissionCheckServiceImpl(), auditLogApi, studentLookupApi, tenantContext, chargeRepository);
		lenient().when(entitlementGrantApi.grantForStaffRecordedPayment(any(), any()))
			.thenReturn(new GrantResult(1, 0, 0, 0));
		as("FINANCE_STAFF");
		lenient().when(tenantContext.getTenantId()).thenReturn(TENANT);
		lenient().when(support.tenantZone()).thenReturn(ZoneId.of("UTC"));
		lenient().when(support.requireStudentUserId(PROFILE)).thenReturn(STUDENT);
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void as(String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(STAFF, TENANT, role, UUID.randomUUID()));
	}

	private static RecordCommand command(String adjustment, String reason, String expectedReceived, UUID key) {
		return new RecordCommand(List.of(new ItemSelection("MONTHLY", 2026, 8, null, null, COURSE)), List.of(),
				"token", adjustment == null ? null : new BigDecimal(adjustment), reason,
				expectedReceived == null ? null : new BigDecimal(expectedReceived), "CASH",
				LocalDate.now(ZoneId.of("UTC")), " RC-1 ", " paid at desk ", key);
	}

	private static CheckoutOrder order(String total, boolean replay) {
		QuoteLine line = new QuoteLine(1, "MONTHLY", "August 2026", 2026, 8, null, null, new BigDecimal(total),
				BigDecimal.ZERO.setScale(2), new BigDecimal(total), COURSE, "Python");
		return new CheckoutOrder(ORDER, STUDENT, COURSE, new BigDecimal(total), "LKR", null, OrderStatus.PLACED,
				Instant.now(), Instant.now(), List.of(line), List.of(), new BigDecimal(total),
				BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), "NONE", null,
				replay, List.of());
	}

	private void persistenceAssignsIds() {
		when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			if (payment.getId() == null) {
				ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
			}
			return payment;
		});
		when(receiptRepository.save(any(ManualPaymentReceipt.class))).thenAnswer(invocation -> {
			ManualPaymentReceipt receipt = invocation.getArgument(0);
			ReflectionTestUtils.setField(receipt, "id", UUID.randomUUID());
			return receipt;
		});
	}

	private static void assertCode(Throwable thrown, HttpStatus status, String code) {
		assertThat(thrown).isInstanceOf(ApplicationException.class);
		assertThat(((ApplicationException) thrown).getHttpStatus()).isEqualTo(status);
		assertThat(((ApplicationException) thrown).getErrorCode()).isEqualTo(code);
	}

	// ------------------------------------------------------------------
	// Happy path.
	// ------------------------------------------------------------------

	@Test
	void recordsConfirmedManualPaymentLedgerReceiptEntitlementsAndAuditInOrder() {
		UUID key = UUID.randomUUID();
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
		when(composerService.createOrderForStudent(eq(STUDENT), any(CheckoutCart.class), eq("token"), eq(key)))
			.thenReturn(order("3000.00", false));
		persistenceAssignsIds();
		UUID profileForAudit = UUID.randomUUID();
		when(studentLookupApi.getStudentSummariesByUserId(List.of(STUDENT)))
			.thenReturn(List.of(new StudentSummary(profileForAudit, STUDENT, "John", "j@x")));

		ReceiptView view = service.record(PROFILE, command("500", " sibling discount ", "2500.00", key));

		ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository, times(2)).save(payment.capture());
		Payment confirmed = payment.getValue();
		assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
		assertThat(confirmed.getAmount()).isEqualByComparingTo("2500.00");
		assertThat(confirmed.getGatewayReference()).isEqualTo("MANUAL-" + confirmed.getId());
		assertThat(confirmed.getTenantId()).isEqualTo(TENANT);
		verify(ledgerEntryApi).recordPaymentConfirmed(ORDER, confirmed.getId(), confirmed.getAmount());
		verify(entitlementGrantApi).grantForStaffRecordedPayment(confirmed.getId(), ORDER);
		verify(discountRedemptionService).consume(ORDER);
		verify(receiptRepository).acquireLock("manual-receipt:" + TENANT + ":" + key);

		assertThat(view.replay()).isFalse();
		assertThat(view.expectedAmount()).isEqualByComparingTo("3000.00");
		assertThat(view.staffAdjustmentAmount()).isEqualByComparingTo("500.00");
		assertThat(view.receivedAmount()).isEqualByComparingTo("2500.00");
		assertThat(view.adjustmentReason()).isEqualTo("sibling discount");
		assertThat(view.referenceNumber()).isEqualTo("RC-1");
		assertThat(view.notes()).isEqualTo("paid at desk");
		assertThat(view.paymentMethod()).isEqualTo("CASH");
		assertThat(view.recordedBy()).isEqualTo(STAFF);
		assertThat(view.lines()).hasSize(1);

		ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi, times(2)).record(audit.capture());
		AuditLogEntry paymentRow = audit.getAllValues().get(0);
		assertThat(paymentRow.action()).isEqualTo("manual_payment.recorded");
		assertThat(paymentRow.actorId()).isEqualTo(STAFF);
		assertThat(paymentRow.targetEntity()).isEqualTo("payment");
		assertThat(paymentRow.targetId()).isEqualTo(confirmed.getId());
		assertThat(paymentRow.reason()).isEqualTo("sibling discount");
		assertThat(paymentRow.metadata()).containsEntry("expectedAmount", "3000.00")
			.containsEntry("staffAdjustmentAmount", "500.00")
			.containsEntry("receivedAmount", "2500.00")
			.containsEntry("method", "CASH")
			.containsEntry("idempotencyKey", key.toString());
		AuditLogEntry profileRow = audit.getAllValues().get(1);
		assertThat(profileRow.targetEntity()).isEqualTo("student_profile");
		assertThat(profileRow.targetId()).isEqualTo(profileForAudit);
	}

	@Test
	void noAdjustmentRecordsTheFullQuotedTotal() {
		UUID key = UUID.randomUUID();
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
		when(composerService.createOrderForStudent(eq(STUDENT), any(CheckoutCart.class), anyString(), eq(key)))
			.thenReturn(order("3000.00", false));
		persistenceAssignsIds();

		ReceiptView view = service.record(PROFILE, command(null, null, "3000", key));

		assertThat(view.receivedAmount()).isEqualByComparingTo("3000.00");
		assertThat(view.staffAdjustmentAmount()).isEqualByComparingTo("0.00");
		assertThat(view.adjustmentReason()).isNull();
	}

	// ------------------------------------------------------------------
	// Amount rules (M3/M4).
	// ------------------------------------------------------------------

	@Test
	void amountMismatchIs409AndNothingIsConfirmed() {
		UUID key = UUID.randomUUID();
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
		when(composerService.createOrderForStudent(eq(STUDENT), any(CheckoutCart.class), anyString(), eq(key)))
			.thenReturn(order("3500.00", false));

		Throwable thrown = org.assertj.core.api.Assertions
			.catchThrowable(() -> service.record(PROFILE, command("500", "sibling", "2500.00", key)));

		assertCode(thrown, HttpStatus.CONFLICT, ManualPaymentException.AMOUNT_MISMATCH);
		verify(paymentRepository, never()).save(any());
		verifyNoInteractions(ledgerEntryApi, entitlementGrantApi, auditLogApi);
		verify(receiptRepository, never()).save(any());
	}

	@Test
	void adjustmentCoveringTheWholeAmountIsRefusedAsFreeCardTerritory() {
		UUID key = UUID.randomUUID();
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
		when(composerService.createOrderForStudent(eq(STUDENT), any(CheckoutCart.class), anyString(), eq(key)))
			.thenReturn(order("3000.00", false));

		Throwable thrown = org.assertj.core.api.Assertions
			.catchThrowable(() -> service.record(PROFILE, command("3000", "free", "0", key)));

		assertCode(thrown, HttpStatus.UNPROCESSABLE_CONTENT, ManualPaymentException.ADJUSTMENT_TOO_LARGE);
		verify(paymentRepository, never()).save(any());
		verifyNoInteractions(ledgerEntryApi);
	}

	// ------------------------------------------------------------------
	// Idempotency (M7).
	// ------------------------------------------------------------------

	private ManualPaymentReceipt storedReceipt(UUID key) {
		ManualPaymentReceipt existing = ManualPaymentReceipt.record(TENANT, STUDENT, ORDER, UUID.randomUUID(),
				PaymentMethodType.CASH, LocalDate.now(ZoneId.of("UTC")), "RC-1", "paid at desk", "LKR",
				new BigDecimal("3000.00"), new BigDecimal("0.00"), null, new BigDecimal("3000.00"), STAFF,
				Instant.now(), key);
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));
		StudentOrderLine line = org.mockito.Mockito.mock(StudentOrderLine.class);
		lenient().when(line.getCoverageKey()).thenReturn(CoverageKeys.monthly(COURSE, 2026, 8));
		when(lineRepository.findAllByOrderId(ORDER)).thenReturn(List.of(line));
		lenient().when(chargeRepository.findAllByOrderId(ORDER)).thenReturn(List.of());
		return existing;
	}

	@Test
	void replayReturnsTheSameReceiptWithoutTouchingTheComposerOrLedger() {
		UUID key = UUID.randomUUID();
		storedReceipt(key);
		when(support.toLines(any())).thenReturn(List.of());

		ReceiptView view = service.record(PROFILE, command(null, null, "3000", key));

		assertThat(view.replay()).isTrue();
		assertThat(view.orderId()).isEqualTo(ORDER);
		verifyNoInteractions(composerService, ledgerEntryApi, entitlementGrantApi, auditLogApi);
		verify(paymentRepository, never()).save(any());
	}

	@Test
	void replayWithDifferentDetailsIs409() {
		// R1: the same key with another amount, method, date, reference, item or postage is not a replay.
		List<RecordCommand> variants = List.of(
				command(null, null, "2999", null),
				withMethod(command(null, null, "3000", null), "CARD"),
				withReference(command(null, null, "3000", null), "RC-2"),
				withReceivedOn(command(null, null, "3000", null), LocalDate.now(ZoneId.of("UTC")).minusDays(1)),
				withItems(command(null, null, "3000", null),
						List.of(new ItemSelection("MONTHLY", 2026, 9, null, null, COURSE))),
				withPostage(command(null, null, "3000", null), List.of(COURSE)));
		for (RecordCommand variant : variants) {
			UUID key = UUID.randomUUID();
			storedReceipt(key);
			RecordCommand keyed = withKey(variant, key);

			Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.record(PROFILE, keyed));

			assertCode(thrown, HttpStatus.CONFLICT, ManualPaymentException.IDEMPOTENCY_KEY_REUSED);
		}
		verifyNoInteractions(composerService, ledgerEntryApi, entitlementGrantApi);
	}

	private static RecordCommand withKey(RecordCommand c, UUID key) {
		return new RecordCommand(c.items(), c.postageCourseIds(), c.quoteToken(), c.staffAdjustmentAmount(),
				c.adjustmentReason(), c.expectedReceivedAmount(), c.paymentMethod(), c.receivedOn(),
				c.referenceNumber(), c.notes(), key);
	}

	private static RecordCommand withMethod(RecordCommand c, String method) {
		return new RecordCommand(c.items(), c.postageCourseIds(), c.quoteToken(), c.staffAdjustmentAmount(),
				c.adjustmentReason(), c.expectedReceivedAmount(), method, c.receivedOn(), c.referenceNumber(),
				c.notes(), c.idempotencyKey());
	}

	private static RecordCommand withReference(RecordCommand c, String reference) {
		return new RecordCommand(c.items(), c.postageCourseIds(), c.quoteToken(), c.staffAdjustmentAmount(),
				c.adjustmentReason(), c.expectedReceivedAmount(), c.paymentMethod(), c.receivedOn(), reference,
				c.notes(), c.idempotencyKey());
	}

	private static RecordCommand withReceivedOn(RecordCommand c, LocalDate receivedOn) {
		return new RecordCommand(c.items(), c.postageCourseIds(), c.quoteToken(), c.staffAdjustmentAmount(),
				c.adjustmentReason(), c.expectedReceivedAmount(), c.paymentMethod(), receivedOn, c.referenceNumber(),
				c.notes(), c.idempotencyKey());
	}

	private static RecordCommand withItems(RecordCommand c, List<ItemSelection> items) {
		return new RecordCommand(items, c.postageCourseIds(), c.quoteToken(), c.staffAdjustmentAmount(),
				c.adjustmentReason(), c.expectedReceivedAmount(), c.paymentMethod(), c.receivedOn(),
				c.referenceNumber(), c.notes(), c.idempotencyKey());
	}

	private static RecordCommand withPostage(RecordCommand c, List<UUID> postage) {
		return new RecordCommand(c.items(), postage, c.quoteToken(), c.staffAdjustmentAmount(),
				c.adjustmentReason(), c.expectedReceivedAmount(), c.paymentMethod(), c.receivedOn(),
				c.referenceNumber(), c.notes(), c.idempotencyKey());
	}

	@Test
	void grantThatSkipsAlreadyCoveredItemRollsTheReceiptBackWith409() {
		// R1: no cash may be confirmed without its entitlement.
		UUID key = UUID.randomUUID();
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
		when(composerService.createOrderForStudent(eq(STUDENT), any(CheckoutCart.class), anyString(), eq(key)))
			.thenReturn(order("3000.00", false));
		persistenceAssignsIds();
		when(entitlementGrantApi.grantForStaffRecordedPayment(any(), eq(ORDER)))
			.thenReturn(new GrantResult(0, 0, 1, 0));

		Throwable thrown = org.assertj.core.api.Assertions
			.catchThrowable(() -> service.record(PROFILE, command(null, null, "3000", key)));

		assertCode(thrown, HttpStatus.CONFLICT, CheckoutItemException.ITEM_ALREADY_PAID);
		verify(discountRedemptionService, never()).consume(any());
		verifyNoInteractions(auditLogApi);
	}

	@Test
	void sameKeyForAnotherStudentIs409() {
		UUID key = UUID.randomUUID();
		ManualPaymentReceipt existing = ManualPaymentReceipt.record(TENANT, UUID.randomUUID(), ORDER,
				UUID.randomUUID(), PaymentMethodType.CASH, LocalDate.now(), null, null, "LKR",
				new BigDecimal("3000.00"), new BigDecimal("0.00"), null, new BigDecimal("3000.00"), STAFF,
				Instant.now(), key);
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

		Throwable thrown = org.assertj.core.api.Assertions
			.catchThrowable(() -> service.record(PROFILE, command(null, null, "3000", key)));

		assertCode(thrown, HttpStatus.CONFLICT, ManualPaymentException.IDEMPOTENCY_KEY_REUSED);
		verifyNoInteractions(composerService);
	}

	@Test
	void keyAlreadyUsedByAStudentOrderIs409() {
		UUID key = UUID.randomUUID();
		when(receiptRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
		when(composerService.createOrderForStudent(eq(STUDENT), any(CheckoutCart.class), anyString(), eq(key)))
			.thenReturn(order("3000.00", true));

		Throwable thrown = org.assertj.core.api.Assertions
			.catchThrowable(() -> service.record(PROFILE, command(null, null, "3000", key)));

		assertCode(thrown, HttpStatus.CONFLICT, ManualPaymentException.IDEMPOTENCY_KEY_REUSED);
		verify(paymentRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// Authorization and student resolution.
	// ------------------------------------------------------------------

	@Test
	void studentAndViewOnlyRolesCannotRecordOrQuote() {
		for (String role : List.of("STUDENT", "STUDENT_SUPPORT", "READ_ONLY_AUDITOR", "TEACHER")) {
			as(role);
			assertThatThrownBy(() -> service.record(PROFILE, command(null, null, "3000", UUID.randomUUID())))
				.as(role)
				.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.quote(PROFILE, new CheckoutCart(null, List.of(), List.of())))
				.as(role)
				.isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(composerService, paymentRepository, ledgerEntryApi, receiptRepository);
	}

	@Test
	void unknownOrForeignStudentIs404BeforeAnythingElse() {
		UUID foreign = UUID.randomUUID();
		when(support.requireStudentUserId(foreign)).thenThrow(new NotFoundException("Student not found"));

		assertThatThrownBy(() -> service.record(foreign, command(null, null, "3000", UUID.randomUUID())))
			.isInstanceOf(NotFoundException.class);
		verifyNoInteractions(composerService, receiptRepository);
	}

	// ------------------------------------------------------------------
	// Field validation (M3, M6, M7).
	// ------------------------------------------------------------------

	private static RecordCommand with(String method, LocalDate receivedOn, String reference, String notes,
			String adjustment, String reason, String expected, UUID key) {
		return new RecordCommand(List.of(), List.of(), "t", adjustment == null ? null : new BigDecimal(adjustment),
				reason, expected == null ? null : new BigDecimal(expected), method, receivedOn, reference, notes, key);
	}

	@Test
	void validationRules() {
		LocalDate today = LocalDate.of(2026, 10, 21);
		UUID key = UUID.randomUUID();
		String longRef = "R".repeat(101);
		String longNotes = "n".repeat(1001);

		assertValidation(with("CASH", today, null, null, null, null, "1", null), "VALIDATION_ERROR", today);
		assertValidation(with("CHEQUE", today, null, null, null, null, "1", key), "VALIDATION_ERROR", today);
		assertValidation(with(null, today, null, null, null, null, "1", key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", null, null, null, null, null, "1", key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", today.plusDays(1), null, null, null, null, "1", key), "VALIDATION_ERROR",
				today);
		assertValidation(with("CASH", today, longRef, null, null, null, "1", key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", today, null, longNotes, null, null, "1", key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", today, null, null, "-1", "x", "1", key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", today, null, null, "10.005", "x", "1", key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", today, null, null, "100", "  ", "1", key), "ADJUSTMENT_REASON_REQUIRED",
				today);
		assertValidation(with("CASH", today, null, null, null, null, null, key), "VALIDATION_ERROR", today);
		assertValidation(with("CASH", today, null, null, null, null, "1.001", key), "VALIDATION_ERROR", today);
		// R1: at most 365 days back.
		assertValidation(with("CASH", today.minusDays(366), null, null, null, null, "1", key), "VALIDATION_ERROR",
				today);
		ManualPaymentService.validate(with("CASH", today.minusDays(365), null, null, null, null, "1", key), today);
		// R1: an adjustment together with postage is refused; postage alone is fine.
		RecordCommand adjustedWithPostage = new RecordCommand(List.of(), List.of(COURSE), "t", new BigDecimal("100"),
				"sibling", new BigDecimal("1"), "CASH", today, null, null, key);
		assertValidation(adjustedWithPostage, "ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED", today);
		ManualPaymentService.validate(new RecordCommand(List.of(), List.of(COURSE), "t", null, null,
				new BigDecimal("1"), "CASH", today, null, null, key), today);

		ManualPaymentService.Validated ok = ManualPaymentService
			.validate(with("bank_transfer", today, "R".repeat(100), null, "0", "ignored", "1.5", key), today);
		assertThat(ok.method()).isEqualTo(PaymentMethodType.BANK_TRANSFER);
		assertThat(ok.adjustment()).isEqualByComparingTo("0.00");
		assertThat(ok.adjustmentReason()).isNull();
		assertThat(ok.expectedReceived()).isEqualByComparingTo("1.50");
	}

	private static void assertValidation(RecordCommand command, String code, LocalDate today) {
		Throwable thrown = org.assertj.core.api.Assertions
			.catchThrowable(() -> ManualPaymentService.validate(command, today));
		assertCode(thrown, HttpStatus.UNPROCESSABLE_CONTENT, code);
	}

}
