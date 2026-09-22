package com.lms.coursemanagement.course.domain;

/**
 * {@code course.pricing_model} (V37) - the billing shape a course uses,
 * distinct from {@link CourseStatus}'s lifecycle/visibility axis. Every
 * course defaults to {@link #ONE_TIME} (V37's column default), matching every
 * course that existed before Wave 2 shipped (a bare, flat {@code
 * course.price}).
 *
 * <ul>
 * <li>{@link #FREE} - no payment ever required; checkout resolves to a
 * $0 amount.</li>
 * <li>{@link #ONE_TIME} - the pre-Wave-2 behavior: {@code course.price} is
 * the checkout amount.</li>
 * <li>{@link #MONTHLY}/{@link #SESSION} - the checkout amount is resolved
 * from the course's current open {@link CourseBillingPeriod}, not {@code
 * course.price}.</li>
 * <li>{@link #CUSTOM} - no system-resolved amount; an authorized staff
 * member must supply one per order (manual quote).</li>
 * </ul>
 */
public enum CoursePricingModel {

	FREE, ONE_TIME, MONTHLY, SESSION, CUSTOM

}
