package com.lms.ledgersettlementmanagement.domain;

/** Mirrors {@code teacher_settlement.ck_teacher_settlement_kind} (V55). */
public enum TeacherSettlementKind {

	/** A calculated statement for one (teacher, closed period) - at most one per period (schema-enforced). */
	REGULAR,

	/** A signed correction referencing a REGULAR settlement; the original row is never mutated. */
	ADJUSTMENT

}
