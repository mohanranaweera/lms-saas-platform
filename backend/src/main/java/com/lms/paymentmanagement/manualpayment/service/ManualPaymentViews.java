package com.lms.paymentmanagement.manualpayment.service;

import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ADR-030 manual payment service-level commands and read views (plan §4).
 * Views are plain records - never JPA entities. Money is {@code BigDecimal}
 * with scale 2.
 */
public final class ManualPaymentViews {

	private ManualPaymentViews() {
	}

	/** {@code POST /staff/students/{studentId}/manual-payments} after binding (no tenant / student / price fields). */
	public record RecordCommand(List<ItemSelection> items, List<UUID> postageCourseIds, String quoteToken,
			BigDecimal staffAdjustmentAmount, String adjustmentReason, BigDecimal expectedReceivedAmount,
			String paymentMethod, LocalDate receivedOn, String referenceNumber, String notes, UUID idempotencyKey) {

		public RecordCommand {
			items = items == null ? List.of() : items;
			postageCourseIds = postageCourseIds == null ? List.of() : postageCourseIds;
		}

	}

	/** One order line as shown on a receipt / in the payment history. */
	public record ReceiptLine(int lineNo, UUID courseId, String courseTitle, String scope, String label, Integer year,
			Integer month, BigDecimal lineTotal) {

	}

	/** The recorded receipt ({@code replay = true} for an idempotent replay, HTTP 200). */
	public record ReceiptView(UUID receiptId, UUID orderId, UUID paymentId, String currency, BigDecimal expectedAmount,
			BigDecimal staffAdjustmentAmount, BigDecimal receivedAmount, String paymentMethod, String referenceNumber,
			LocalDate receivedOn, String notes, String adjustmentReason, UUID recordedBy, Instant recordedAt,
			List<ReceiptLine> lines, boolean replay) {

	}

	/** Receipt details on a history row ({@code recordedByName} = best-effort staff email, may be null). */
	public record HistoryReceipt(UUID receiptId, String method, String referenceNumber, LocalDate receivedOn,
			String notes, String adjustmentReason, UUID recordedBy, String recordedByName, Instant recordedAt) {

	}

	/**
	 * One order of the student with at least one line for the selected course.
	 * {@code status}: RECEIVED | PENDING_SLIP | PENDING_ONLINE | REJECTED;
	 * {@code source}: MANUAL | SLIP | GATEWAY | OTHER.
	 */
	public record HistoryEntry(UUID orderId, Instant createdAt, String status, String source, String currency,
			BigDecimal orderTotal, BigDecimal receivedAmount, BigDecimal staffAdjustmentAmount,
			BigDecimal refundedAmount, UUID paymentId, UUID slipId, HistoryReceipt receipt, List<ReceiptLine> lines) {

	}

	/** {@code studentId} is the student_profile id the URL addressed. */
	public record StudentInfo(UUID studentId, String name, String email, String mobile) {

	}

	public record ContextCourse(UUID courseId, String title, String pricingModel, BigDecimal monthlyAmount,
			String currency, BigDecimal postageFee) {

	}

	public record PaymentContext(StudentInfo student, String timezone, List<ContextCourse> courses) {

	}

}
