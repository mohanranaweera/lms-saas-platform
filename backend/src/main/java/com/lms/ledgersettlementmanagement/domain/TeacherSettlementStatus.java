package com.lms.ledgersettlementmanagement.domain;

/**
 * Mirrors {@code teacher_settlement.ck_teacher_settlement_status} (V55).
 * One-way: {@code CALCULATED -> PAID}. {@code PAID} is record-keeping only -
 * the platform moves no money and writes no ledger entry (wave-07-plan.md §10
 * judgment call 8).
 */
public enum TeacherSettlementStatus {

	CALCULATED, PAID,

	/**
	 * TEACHER-WALLET-01 (V98): a wallet entry. Counts toward the wallet balance like an unpaid
	 * settlement, but is final - it is never marked paid.
	 */
	POSTED

}
