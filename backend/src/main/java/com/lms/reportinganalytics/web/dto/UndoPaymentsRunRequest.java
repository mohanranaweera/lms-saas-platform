package com.lms.reportinganalytics.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UndoPaymentsRunRequest(List<UUID> courseIds, LocalDate startDate, LocalDate endDate) {
}
