package com.lms.coursemanagement.api;

/**
 * {@code course_lesson.payment_type} (V85, D03/D05, ADR-028) - the leaf
 * payment policy of an admin-UI "Session". Kept in this {@code api} package
 * so other modules (the lesson access evaluator, the payment composer) can
 * read it without importing course-management's {@code domain} package.
 *
 * <ul>
 * <li>{@link #FREE} - no payment ever needed for this lesson.</li>
 * <li>{@link #COVERED_BY_CLASS_FEES} - covered by the course's class fees
 * (legacy course access, or a MONTHLY entitlement for the lesson's explicit
 * billing month, or a MODULE entitlement). The column default.</li>
 * <li>{@link #PAID} - needs its own LESSON entitlement; carries a price
 * {@code > 0}.</li>
 * </ul>
 */
public enum LessonPaymentType {

	FREE, COVERED_BY_CLASS_FEES, PAID

}
