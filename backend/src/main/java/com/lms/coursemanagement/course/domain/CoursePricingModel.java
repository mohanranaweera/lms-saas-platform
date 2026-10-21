package com.lms.coursemanagement.course.domain;

/**
 * {@code course.pricing_model} (V37, extended by V85) - the billing shape a
 * course uses, distinct from {@link CourseStatus}'s lifecycle/visibility
 * axis. Every course defaults to {@link #ONE_TIME} (V37's column default).
 *
 * <ul>
 * <li>{@link #FREE} - no course fee ("Free").</li>
 * <li>{@link #ONE_TIME} - {@code course.price} is the whole-course amount
 * ("Full Course").</li>
 * <li>{@link #MONTHLY} - priced per billing month from the course's current
 * open {@link CourseBillingPeriod} ("Monthly Fee").</li>
 * <li>{@link #SESSION} - legacy: current open billing period per session.
 * Readable for existing courses, not offered on create (D09).</li>
 * <li>{@link #CUSTOM} - legacy: staff-supplied manual quote. Readable for
 * existing courses, not offered on create (D09).</li>
 * <li>{@link #MODULE} - sold per module ({@code course_module.price},
 * "Pay For Module").</li>
 * <li>{@link #DAILY} - "Day payments": stored only; no offer engine exists
 * yet (D09).</li>
 * <li>{@link #INSTALLMENT} - "Installments": stored only; no offer engine
 * exists yet (D09).</li>
 * </ul>
 * None of DAILY/MODULE/INSTALLMENT has a whole-course checkout amount.
 */
public enum CoursePricingModel {

	FREE, ONE_TIME, MONTHLY, SESSION, CUSTOM, DAILY, MODULE, INSTALLMENT;

	/** The six models the "Add New Course" form offers (D09). */
	public boolean selectableOnCreate() {
		return this != SESSION && this != CUSTOM;
	}

}
