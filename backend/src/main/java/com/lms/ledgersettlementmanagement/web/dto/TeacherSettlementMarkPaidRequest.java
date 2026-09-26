package com.lms.ledgersettlementmanagement.web.dto;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/finance/teacher-settlements/{id}/mark-paid}. Record-keeping only. */
public record TeacherSettlementMarkPaidRequest(@Size(max = 255) String payoutReference) {

}
