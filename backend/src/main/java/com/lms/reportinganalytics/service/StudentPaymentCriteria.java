package com.lms.reportinganalytics.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Period;
import java.util.List;
import java.util.UUID;

/**
 * Validated RPT-02 filter (output of {@link StudentPaymentRequestValidator}).
 * {@code courseIds} are verified courses of the caller's tenant, de-duplicated
 * in request order; empty = all courses. {@code paymentMethod} null = all cash
 * methods. {@code period} carries only the active mode's fields.
 */
public record StudentPaymentCriteria(List<UUID> courseIds, PaymentMethod paymentMethod, Period period) {

	public StudentPaymentCriteria {
		courseIds = courseIds == null ? List.of() : List.copyOf(courseIds);
	}

	public boolean allCourses() {
		return courseIds.isEmpty();
	}

}
