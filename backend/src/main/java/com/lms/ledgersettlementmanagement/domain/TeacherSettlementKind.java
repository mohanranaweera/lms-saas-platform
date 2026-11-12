package com.lms.ledgersettlementmanagement.domain;

/** Mirrors {@code teacher_settlement.ck_teacher_settlement_kind} (V55, widened in V98). */
public enum TeacherSettlementKind {

	/** A calculated statement for one (teacher, closed period) - at most one per period (schema-enforced). */
	REGULAR,

	/** A signed correction referencing a REGULAR settlement; the original row is never mutated. */
	ADJUSTMENT,

	/**
	 * TEACHER-WALLET-01 (ADR-036): the wallet's opening balance, recorded once at wallet creation
	 * ({@code >= 0}). Not a cash movement.
	 */
	OPENING,

	/** Wallet entry increasing the amount payable to the teacher ({@code > 0}). */
	PAY_IN,

	/** Wallet entry recording a payout to the teacher ({@code < 0}); never takes the balance below zero. */
	PAY_OUT,

	/** Signed wallet balance correction with a mandatory reason (the balance pencil). */
	BALANCE_ADJ;

	/** {@code true} for the TEACHER-WALLET-01 kinds, which are POSTED once and never marked paid. */
	public boolean isWalletEntry() {
		return this == OPENING || this == PAY_IN || this == PAY_OUT || this == BALANCE_ADJ;
	}

}
