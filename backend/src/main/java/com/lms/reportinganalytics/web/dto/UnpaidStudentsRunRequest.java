package com.lms.reportinganalytics.web.dto;

import java.util.UUID;

/** Plain request values; server validates tenant course and purchased month. */
public record UnpaidStudentsRunRequest(UUID courseId, String billingMonth, Boolean atLeastOneAttended) {

}
