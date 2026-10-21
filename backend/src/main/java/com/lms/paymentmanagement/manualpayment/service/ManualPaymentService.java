package com.lms.paymentmanagement.manualpayment.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi.GrantResult;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.checkout.service.CheckoutComposerService;
import com.lms.paymentmanagement.checkout.service.CheckoutItemException;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCart;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOrder;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutQuote;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.QuoteLine;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService;
import com.lms.paymentmanagement.manualpayment.domain.ManualPaymentReceipt;
import com.lms.paymentmanagement.manualpayment.domain.PaymentMethodType;
import com.lms.paymentmanagement.manualpayment.repository.ManualPaymentReceiptRepository;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.ReceiptLine;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.ReceiptView;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.RecordCommand;
import com.lms.paymentmanagement.order.domain.StudentOrderCourseCharge;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import com.lms.paymentmanagement.order.repository.StudentOrderCourseChargeRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-030 (M1-M8): staff-recorded manual payment receipts.
 *
 * <p>{@link #record} is ONE transaction: permission ({@code
 * PAYMENTS_SLIPS/APPROVE}) - server-side student resolution (tenant-scoped,
 * 404) - request validation - advisory lock on (tenant, idempotency key) and
 * replay - the composer line order for that student ({@link
 * CheckoutComposerService#createOrderForStudent}: coverage locks, re-quote,
 * quote token) - amount rules (M3/M4) - one {@code MANUAL-} payment confirmed
 * with the cash received - the existing {@code PAYMENT_CONFIRMED} ledger entry
 * - the immutable receipt row - line entitlements ({@link
 * EntitlementGrantApi#grantForStaffRecordedPayment}) - discount uses CONSUMED -
 * audit rows. Any failure rolls everything back (nothing persisted on {@code
 * AMOUNT_MISMATCH}). A pending slip / online payment for an item is the
 * composer's {@code ITEM_PENDING}: a slip is never auto-approved (M5).
 *
 * <p>Per {@code .claude/rules/payments.md} §8 the permission is a category
 * grant only; the payment/ledger rules above are enforced independently.
 */
@Service
public class ManualPaymentService {

	static final String GATEWAY_REFERENCE_PREFIX = "MANUAL-";

	static final String AUDIT_ACTION = "manual_payment.recorded";

	static final int MAX_REFERENCE = 100;

	static final int MAX_NOTES = 1000;

	static final int MAX_REASON = 1000;

	/** R1: a receipt may be back-dated at most this many days (tenant timezone). */
	static final int MAX_RECEIVED_AGE_DAYS = 365;

	private final ManualPaymentSupport support;

	private final ManualPaymentReceiptRepository receiptRepository;

	private final CheckoutComposerService composerService;

	private final PaymentRepository paymentRepository;

	private final LedgerEntryApi ledgerEntryApi;

	private final EntitlementGrantApi entitlementGrantApi;

	private final DiscountRedemptionService discountRedemptionService;

	private final StudentOrderLineRepository studentOrderLineRepository;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final StudentLookupApi studentLookupApi;

	private final TenantContext tenantContext;

	private final StudentOrderCourseChargeRepository studentOrderCourseChargeRepository;

	public ManualPaymentService(ManualPaymentSupport support, ManualPaymentReceiptRepository receiptRepository,
			CheckoutComposerService composerService, PaymentRepository paymentRepository,
			LedgerEntryApi ledgerEntryApi, EntitlementGrantApi entitlementGrantApi,
			DiscountRedemptionService discountRedemptionService,
			StudentOrderLineRepository studentOrderLineRepository, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, StudentLookupApi studentLookupApi, TenantContext tenantContext,
			StudentOrderCourseChargeRepository studentOrderCourseChargeRepository) {
		this.studentOrderCourseChargeRepository = studentOrderCourseChargeRepository;
		this.support = support;
		this.receiptRepository = receiptRepository;
		this.composerService = composerService;
		this.paymentRepository = paymentRepository;
		this.ledgerEntryApi = ledgerEntryApi;
		this.entitlementGrantApi = entitlementGrantApi;
		this.discountRedemptionService = discountRedemptionService;
		this.studentOrderLineRepository = studentOrderLineRepository;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.studentLookupApi = studentLookupApi;
		this.tenantContext = tenantContext;
	}

	/** The staff quote for the student's selection (no code, no rewards - M1). Never persists anything. */
	@Transactional(readOnly = true)
	public CheckoutQuote quote(UUID studentProfileId, CheckoutCart cart) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);
		UUID studentUserId = support.requireStudentUserId(studentProfileId);
		return composerService.quoteForStudent(studentUserId, cart);
	}

	@Transactional
	public ReceiptView record(UUID studentProfileId, RecordCommand command) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE);
		UUID studentUserId = support.requireStudentUserId(studentProfileId);
		Validated input = validate(command, LocalDate.now(support.tenantZone()));
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		// M7: serialize on (tenant, key); a committed receipt with the key is a replay.
		receiptRepository.acquireLock("manual-receipt:" + tenantId + ":" + input.idempotencyKey());
		Optional<ManualPaymentReceipt> existing = receiptRepository.findByIdempotencyKey(input.idempotencyKey());
		if (existing.isPresent()) {
			ManualPaymentReceipt receipt = existing.get();
			List<StudentOrderLine> lines = studentOrderLineRepository.findAllByOrderId(receipt.getOrderId());
			if (!sameReceipt(receipt, lines, studentUserId, command, input)) {
				throw keyReused();
			}
			return toView(receipt, support.toLines(lines), true);
		}

		CheckoutCart cart = new CheckoutCart(null, command.items(), command.postageCourseIds(), null, List.of());
		CheckoutOrder order = composerService.createOrderForStudent(studentUserId, cart, command.quoteToken(),
				input.idempotencyKey());
		if (order.idempotentReplay()) {
			// The key belongs to an order placed earlier (not by this receipt flow).
			throw keyReused();
		}

		BigDecimal expected = money(order.amount());
		BigDecimal adjustment = input.adjustment();
		if (adjustment.compareTo(expected) >= 0) {
			throw new ManualPaymentException(HttpStatus.UNPROCESSABLE_CONTENT,
					ManualPaymentException.ADJUSTMENT_TOO_LARGE,
					"The adjustment must be less than the amount due - to give these items free of charge, "
							+ "issue a Free Card instead");
		}
		BigDecimal received = expected.subtract(adjustment);
		if (received.compareTo(input.expectedReceived()) != 0) {
			// Thrown inside the transaction: the order just placed rolls back too.
			throw new ManualPaymentException(HttpStatus.CONFLICT, ManualPaymentException.AMOUNT_MISMATCH,
					"The amount to receive is now " + received.toPlainString() + " " + order.currency()
							+ " - review the updated amount and try again");
		}

		Instant now = Instant.now();
		Payment payment = paymentRepository.save(new Payment(tenantId, order.id(), received, order.currency()));
		payment.assignGatewayReference(GATEWAY_REFERENCE_PREFIX + payment.getId());
		payment.confirm(now);
		payment = paymentRepository.save(payment);
		ledgerEntryApi.recordPaymentConfirmed(order.id(), payment.getId(), payment.getAmount());

		ManualPaymentReceipt receipt = receiptRepository.save(ManualPaymentReceipt.record(tenantId, studentUserId,
				order.id(), payment.getId(), input.method(), input.receivedOn(), input.reference(), input.notes(),
				order.currency(), expected, adjustment, input.adjustmentReason(), received, actorId, now,
				input.idempotencyKey()));

		// Same transaction: a grant failure rolls the payment, ledger entry and receipt back.
		GrantResult grant = entitlementGrantApi.grantForStaffRecordedPayment(payment.getId(), order.id());
		if (grant.skippedActiveCoverage() > 0) {
			// R1: no cash may be confirmed without its entitlement - roll the whole receipt back.
			throw new CheckoutItemException(HttpStatus.CONFLICT, CheckoutItemException.ITEM_ALREADY_PAID,
					"One of the selected items is already paid for - refresh the student's payments and try again");
		}
		// DISC-01 (D12): the order's automatic-discount uses become CONSUMED.
		discountRedemptionService.consume(order.id());

		List<ReceiptLine> lines = order.lines().stream().map(ManualPaymentService::toLine).toList();
		audit(actorId, studentUserId, receipt, lines);
		return toView(receipt, lines, false);
	}

	private void audit(UUID actorId, UUID studentUserId, ManualPaymentReceipt receipt, List<ReceiptLine> lines) {
		Set<String> courses = new LinkedHashSet<>();
		lines.forEach(line -> courses.add(line.courseId().toString()));
		Map<String, Object> metadata = metadata("studentId", studentUserId, "orderId", receipt.getOrderId(),
				"receiptId", receipt.getId(), "courseIds", List.copyOf(courses), "method",
				receipt.getPaymentMethod().name(), "receivedOn", receipt.getReceivedOn().toString(), "referenceNumber",
				receipt.getReferenceNumber(), "currency", receipt.getCurrency(), "expectedAmount",
				receipt.getExpectedAmount().toPlainString(), "staffAdjustmentAmount",
				receipt.getStaffAdjustmentAmount().toPlainString(), "receivedAmount",
				receipt.getReceivedAmount().toPlainString(), "idempotencyKey", receipt.getIdempotencyKey());
		auditLogApi.record(new AuditLogEntry(actorId, AUDIT_ACTION, "payment", receipt.getPaymentId(),
				receipt.getAdjustmentReason(), metadata));
		// Second row on the student's own profile so it shows on the student's Activity tab
		// (same pattern as ManualEnrollmentService).
		studentLookupApi.getStudentSummariesByUserId(List.of(studentUserId))
			.stream()
			.findFirst()
			.map(StudentSummary::studentProfileId)
			.ifPresent(profileId -> auditLogApi.record(new AuditLogEntry(actorId, AUDIT_ACTION, "student_profile",
					profileId, receipt.getAdjustmentReason(),
					metadata("paymentId", receipt.getPaymentId(), "orderId", receipt.getOrderId(), "receiptId",
							receipt.getId(), "courseIds", List.copyOf(courses), "receivedAmount",
							receipt.getReceivedAmount().toPlainString(), "currency", receipt.getCurrency()))));
	}

	/**
	 * R1 replay hardening: a replayed request must describe the stored receipt
	 * exactly - same student, same sorted coverage keys and postage courses on
	 * its order, same method, adjustment, received amount, received date and
	 * reference. Anything else is 409 {@code IDEMPOTENCY_KEY_REUSED}.
	 */
	private boolean sameReceipt(ManualPaymentReceipt receipt, List<StudentOrderLine> lines, UUID studentUserId,
			RecordCommand command, Validated input) {
		if (!receipt.getStudentId().equals(studentUserId)) {
			return false;
		}
		Set<String> storedKeys = new TreeSet<>();
		lines.forEach(line -> storedKeys.add(line.getCoverageKey()));
		Set<String> requestedKeys = new TreeSet<>();
		for (ItemSelection item : command.items()) {
			String key = coverageKeyOf(item);
			if (key == null) {
				return false;
			}
			requestedKeys.add(key);
		}
		Set<UUID> storedPostage = new TreeSet<>();
		studentOrderCourseChargeRepository.findAllByOrderId(receipt.getOrderId())
			.stream()
			.filter(StudentOrderCourseCharge::isPostageSelected)
			.forEach(charge -> storedPostage.add(charge.getCourseId()));
		Set<UUID> requestedPostage = new TreeSet<>();
		command.postageCourseIds().stream().filter(Objects::nonNull).forEach(requestedPostage::add);
		return storedKeys.equals(requestedKeys) && storedPostage.equals(requestedPostage)
				&& receipt.getPaymentMethod() == input.method()
				&& receipt.getStaffAdjustmentAmount().compareTo(input.adjustment()) == 0
				&& receipt.getReceivedAmount().compareTo(input.expectedReceived()) == 0
				&& receipt.getReceivedOn().equals(input.receivedOn())
				&& Objects.equals(receipt.getReferenceNumber(), input.reference());
	}

	/** The coverage key of a staff item ({@code null} when malformed); staff carts have no default course. */
	static String coverageKeyOf(ItemSelection item) {
		if (item == null || item.scope() == null) {
			return null;
		}
		return switch (item.scope().strip().toUpperCase(Locale.ROOT)) {
			case "MONTHLY" -> item.courseId() != null && item.year() != null && item.month() != null
					&& item.month() >= 1 && item.month() <= 12
							? CoverageKeys.monthly(item.courseId(), item.year(), item.month()) : null;
			case "MODULE" -> item.moduleId() != null ? CoverageKeys.module(item.moduleId()) : null;
			case "LESSON" -> item.lessonId() != null ? CoverageKeys.lesson(item.lessonId()) : null;
			default -> null;
		};
	}

	// ------------------------------------------------------------------
	// Validation (M3, M4, M6, M7).
	// ------------------------------------------------------------------

	record Validated(PaymentMethodType method, LocalDate receivedOn, String reference, String notes,
			BigDecimal adjustment, String adjustmentReason, BigDecimal expectedReceived, UUID idempotencyKey) {

	}

	static Validated validate(RecordCommand command, LocalDate today) {
		if (command.idempotencyKey() == null) {
			throw ManualPaymentException.validation("idempotencyKey is required");
		}
		PaymentMethodType method = parseMethod(command.paymentMethod());
		LocalDate receivedOn = command.receivedOn();
		if (receivedOn == null) {
			throw ManualPaymentException.validation("The date the payment was received is required");
		}
		if (receivedOn.isAfter(today)) {
			throw ManualPaymentException.validation("The received date cannot be in the future");
		}
		if (receivedOn.isBefore(today.minusDays(MAX_RECEIVED_AGE_DAYS))) {
			throw ManualPaymentException.validation(
					"The received date cannot be more than " + MAX_RECEIVED_AGE_DAYS + " days ago");
		}
		String reference = trimToNull(command.referenceNumber());
		if (reference != null && reference.length() > MAX_REFERENCE) {
			throw ManualPaymentException.validation("The reference number is at most " + MAX_REFERENCE + " characters");
		}
		String notes = trimToNull(command.notes());
		if (notes != null && notes.length() > MAX_NOTES) {
			throw ManualPaymentException.validation("Notes are at most " + MAX_NOTES + " characters");
		}
		BigDecimal adjustment = command.staffAdjustmentAmount() == null ? BigDecimal.ZERO
				: command.staffAdjustmentAmount();
		requireMoney(adjustment, "staffAdjustmentAmount");
		if (adjustment.signum() < 0) {
			throw ManualPaymentException.validation("The adjustment cannot be negative");
		}
		String reason = trimToNull(command.adjustmentReason());
		if (adjustment.signum() > 0 && reason == null) {
			throw new ManualPaymentException(HttpStatus.UNPROCESSABLE_CONTENT,
					ManualPaymentException.ADJUSTMENT_REASON_REQUIRED, "A reason is required for an adjustment");
		}
		if (adjustment.signum() > 0 && command.postageCourseIds().stream().anyMatch(Objects::nonNull)) {
			// R1: ADR-029 pro-rata allocation would under-recover postage; the split is an open owner decision.
			throw new ManualPaymentException(HttpStatus.UNPROCESSABLE_CONTENT,
					ManualPaymentException.ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED,
					"An adjustment cannot be combined with postage - record postage without an adjustment");
		}
		if (reason != null && reason.length() > MAX_REASON) {
			throw ManualPaymentException.validation("The adjustment reason is at most " + MAX_REASON + " characters");
		}
		if (command.expectedReceivedAmount() == null) {
			throw ManualPaymentException.validation("expectedReceivedAmount is required");
		}
		requireMoney(command.expectedReceivedAmount(), "expectedReceivedAmount");
		return new Validated(method, receivedOn, reference, notes, money(adjustment),
				adjustment.signum() > 0 ? reason : null, money(command.expectedReceivedAmount()),
				command.idempotencyKey());
	}

	private static PaymentMethodType parseMethod(String value) {
		if (value == null || value.isBlank()) {
			throw ManualPaymentException.validation("The payment method is required");
		}
		try {
			return PaymentMethodType.valueOf(value.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw ManualPaymentException.validation("Unknown payment method (use CASH, BANK_TRANSFER or CARD)");
		}
	}

	/** At most 2 decimal places and within NUMERIC(12,2). */
	private static void requireMoney(BigDecimal value, String field) {
		BigDecimal stripped = value.stripTrailingZeros();
		if (stripped.scale() > 2 || value.abs().compareTo(new BigDecimal("9999999999.99")) > 0) {
			throw ManualPaymentException.validation(field + " must be an amount with at most 2 decimal places");
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

	private static ManualPaymentException keyReused() {
		return new ManualPaymentException(HttpStatus.CONFLICT, ManualPaymentException.IDEMPOTENCY_KEY_REUSED,
				"This payment form was already used for a different payment - reopen it and try again");
	}

	private static ReceiptLine toLine(QuoteLine line) {
		return new ReceiptLine(line.lineNo(), line.courseId(), line.courseTitle(), line.scope(), line.label(),
				line.year(), line.month(), line.lineTotal());
	}

	static ReceiptView toView(ManualPaymentReceipt receipt, List<ReceiptLine> lines, boolean replay) {
		return new ReceiptView(receipt.getId(), receipt.getOrderId(), receipt.getPaymentId(), receipt.getCurrency(),
				receipt.getExpectedAmount(), receipt.getStaffAdjustmentAmount(), receipt.getReceivedAmount(),
				receipt.getPaymentMethod().name(), receipt.getReferenceNumber(), receipt.getReceivedOn(),
				receipt.getNotes(), receipt.getAdjustmentReason(), receipt.getRecordedBy(), receipt.getRecordedAt(),
				lines, replay);
	}

	/** Key/value pairs with {@code null} values dropped (AuditLogEntry copies via {@code Map.copyOf}). */
	private static Map<String, Object> metadata(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			Object value = keyValues[i + 1];
			if (value != null) {
				result.put((String) keyValues[i], value instanceof UUID ? value.toString() : value);
			}
		}
		return result;
	}

}
