package com.lms.coursemanagement.course.service;

import java.util.List;

/**
 * Read-only answer to "can a student pay for this course right now?" - see
 * {@link CoursePaymentReadinessService}. {@code payable} is {@code true} when
 * checkout would list at least one offer for the course (or the course needs
 * no payment at all); {@code issues} names every configuration gap found for
 * the course's pricing model, and may be non-empty even when another offer
 * keeps the course payable.
 */
public record CoursePaymentReadinessView(String pricingModel, boolean payable, List<Issue> issues) {

	/** One configuration gap. {@code count} is set only where a number of rows is meaningful. */
	public record Issue(String code, String message, Integer count) {

	}

}
