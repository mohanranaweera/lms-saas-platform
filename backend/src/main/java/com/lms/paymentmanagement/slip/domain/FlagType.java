package com.lms.paymentmanagement.slip.domain;

/**
 * Mirrors {@code payment_slip_flag}'s {@code ck_payment_slip_flag_flag_type}
 * CHECK constraint (V21) exactly. MVP scope is exact-match duplicate
 * detection only (spec 25 §10) - OCR-derived flag types are explicitly
 * Phase 3 and must not be added here without a new ADR, mirroring {@code
 * ledger_entry.entry_type}'s "adding a third value needs an ADR" precedent.
 */
public enum FlagType {

	DUPLICATE_REFERENCE, DUPLICATE_IMAGE_HASH

}
