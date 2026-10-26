package com.lms.reportinganalytics.service;

import java.util.List;
import java.util.UUID;

/** Stored header and complete ordered row set; preview pages and PDF never recompute it. */
public record RegistrationRun(RegistrationReport report, List<RegistrationRow> rows) {
	public RegistrationRun { rows = rows == null ? List.of() : List.copyOf(rows); }
	public UUID runId() { return report == null ? null : report.runId(); }
}
