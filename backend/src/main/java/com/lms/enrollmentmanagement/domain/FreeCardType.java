package com.lms.enrollmentmanagement.domain;

/**
 * ADR-030 (F2): the Free Card type. Only {@code FULL} is issuable; {@code
 * HALF} is observed in Klass but would be a 50 % pricing rule and is refused
 * (422 {@code FREE_CARD_TYPE_UNSUPPORTED}) - it is deliberately not a value of
 * this enum, so it can never be persisted (V94 CHECK allows {@code FULL} only).
 */
public enum FreeCardType {

	FULL

}
