package com.lms.ledgersettlementmanagement.domain;

/**
 * Mirrors {@code ledger_entry}'s {@code ck_ledger_entry_type} CHECK
 * constraint (V19) exactly - the minimal two-value set PAY-3/PAY-4 actually
 * require. Per {@code .claude/rules/payments.md} §4, adding a third type
 * requires an ADR before the CHECK constraint changes, even for a
 * seemingly small addition (e.g. distinguishing a manual-slip-sourced
 * confirmation from a gateway one) - do not add one here under time
 * pressure.
 */
public enum LedgerEntryType {

	/** A confirmed-payment credit - written once per {@code Payment} reaching {@code CONFIRMED}. */
	PAYMENT_CONFIRMED,

	/** A refund/reversal debit - written once per {@code payment_refund} row, with {@code reversesEntryId} set. */
	REFUND

}
