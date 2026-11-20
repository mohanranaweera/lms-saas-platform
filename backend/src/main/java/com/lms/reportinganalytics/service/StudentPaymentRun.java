package com.lms.reportinganalytics.service;

import java.util.List;
import java.util.UUID;

/**
 * The stored RPT-02 run: the header plus every detail row in base order
 * (currency, payment date, entry id, line no). Previews, grouped pages and the
 * PDF all read only this snapshot - nothing is recomputed.
 */
public record StudentPaymentRun(StudentPaymentReport report, List<StudentPaymentRow> rows) {

	public StudentPaymentRun {
		rows = rows == null ? List.of() : List.copyOf(rows);
	}

	public UUID runId() {
		return report == null ? null : report.runId();
	}

}
