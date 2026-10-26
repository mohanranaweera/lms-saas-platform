package com.lms.reportinganalytics.service;

import java.time.YearMonth;
import java.util.UUID;

/** Validated single-course, single-purchased-month report filters. */
public record UnpaidStudentsCriteria(UUID courseId, YearMonth billingMonth, boolean atLeastOneAttended) {

}
