package com.lms.reportinganalytics.web.dto;

import java.util.UUID;

/** Plain values; tenant course and month validity are enforced server-side. */
public record StudentBehaviorRunRequest(UUID courseId, String billingMonth) {
}
