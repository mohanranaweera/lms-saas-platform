package com.lms.enrollmentmanagement.domain;

/**
 * Mirrors {@code reactivation_request}'s {@code
 * ck_reactivation_request_status} CHECK constraint (V22) exactly. Legal
 * transitions are one-directional: {@code SUBMITTED -> APPROVED|REJECTED} -
 * no {@code UNDER_REVIEW} state (unlike {@code payment_slip}'s shape) per
 * the approved plan §8 sketch. No code path in {@link ReactivationRequest}
 * allows any other transition, including a backward one.
 */
public enum ReactivationRequestStatus {

	SUBMITTED, APPROVED, REJECTED

}
