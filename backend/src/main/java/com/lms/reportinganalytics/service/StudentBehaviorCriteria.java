package com.lms.reportinganalytics.service;

import java.time.YearMonth;
import java.util.UUID;

public record StudentBehaviorCriteria(UUID courseId, YearMonth billingMonth) {
}
