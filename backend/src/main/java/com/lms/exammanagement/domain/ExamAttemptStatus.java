package com.lms.exammanagement.domain;

/**
 * Mirrors {@code ck_exam_attempt_status} (V26) exactly. {@code IN_PROGRESS} ->
 * {@code SUBMITTED} is the only transition {@code ExamAttemptService} ever
 * writes at MVP; {@code EXPIRED} is reserved for a future auto-expiry
 * mechanism the plan explicitly does NOT build yet (MVP-017 §21 item 7 - no
 * auto-submit-on-timeout is invented at this stage), so no code path in this
 * module writes {@code EXPIRED} today.
 */
public enum ExamAttemptStatus {

	IN_PROGRESS, SUBMITTED, EXPIRED

}
