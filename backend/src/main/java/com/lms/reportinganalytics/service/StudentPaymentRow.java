package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One RPT-02 detail row as stored in the run (spec section 3): one (ledger
 * entry, course, order line) - or (entry, course) for a legacy order. Every
 * value is fixed at run time; grouping and export only reorder rows.
 *
 * @param receiptId the payment id (refund rows carry the original payment's id).
 * @param scope {@code MONTHLY|MODULE|LESSON|LEGACY}.
 * @param method {@code ONLINE|BANK_TRANSFER|CASH|CARD|UNKNOWN}.
 * @param manual {@code true} for an ADR-030 staff-recorded receipt.
 * @param paymentDate {@code YYYY-MM-DD} when {@code dateOnly} (manual receipt
 * received date, tenant-local), else the ledger instant (ISO-8601, UTC).
 * @param recordedAt the ledger entry time.
 * @param amount course payment (net of discounts; negative on refund rows).
 * @param lineNo order line number (0 for a legacy order) - ordering only.
 * @param sortAt ordering instant (a date-only payment sorts at its local midnight).
 */
public record StudentPaymentRow(String currency, UUID entryId, UUID receiptId, boolean refund, UUID studentId,
		String studentName, String indexNumber, UUID courseId, String courseName, String reason, String scope,
		String method, boolean manual, String paymentDate, boolean dateOnly, Instant recordedAt, String amount,
		String serviceFee, String postage, int lineNo, Instant sortAt) {

}
