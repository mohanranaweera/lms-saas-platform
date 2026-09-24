package com.lms.liveclassmanagement.domain;

/**
 * {@code class_session.status} (V49's {@code ck_class_session_status} CHECK
 * constraint). Legal transitions are enforced by {@link ClassSession}'s own
 * {@code start()}/{@code complete()}/{@code cancel()} methods, never by this
 * enum itself: {@code SCHEDULED -> LIVE -> COMPLETED}, and {@code SCHEDULED}
 * or {@code LIVE -> CANCELLED}. There is no reverse transition.
 */
public enum ClassSessionStatus {

	SCHEDULED, LIVE, COMPLETED, CANCELLED

}
