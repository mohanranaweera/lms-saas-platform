package com.lms.exammanagement.domain;

/** {@code exam_enrollment.source} (V113, ADR-032). */
public enum ExamEnrollmentSource {

	/** Added by staff (audited). */
	MANUAL,

	/** Materialized for an active course participant of an "Enroll all students" exam (D4). */
	ENROLL_ALL,

	/** A confirmed payment (or an effective Free Card for month criteria) matched the exam's criteria. */
	PAYMENT

}
