package com.lms.reportinganalytics.service;

import java.util.List;
import java.util.UUID;

public record UndoPaymentRun(UndoPaymentReport report, List<UndoPaymentRow> rows) {
	public UndoPaymentRun { rows = rows == null ? List.of() : List.copyOf(rows); }
	public UUID runId() { return report == null ? null : report.runId(); }
}
