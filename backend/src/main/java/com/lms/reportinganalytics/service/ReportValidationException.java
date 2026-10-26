package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * RPT-01: {@code 422} with one field error per invalid input, in the standard
 * {@code ApiResponse} error shape (same pattern as DISC-01's {@code
 * DiscountAdminException.Validation}).
 */
public class ReportValidationException extends FieldValidationException {

	public static final String REPORT_VALIDATION = "REPORT_VALIDATION";

	public static final String REPORT_TOO_LARGE = "REPORT_TOO_LARGE";

	public ReportValidationException(List<FieldError> fieldErrors) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, REPORT_VALIDATION, "The report filter has invalid fields",
				fieldErrors);
	}

	private ReportValidationException(String code, String message, List<FieldError> fieldErrors) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, code, message, fieldErrors);
	}

	public static ReportValidationException tooLarge(int rows, int max) {
		return new ReportValidationException(REPORT_TOO_LARGE, "The report is too large",
				List.of(new FieldError("runId", "has " + rows + " course rows; at most " + max
						+ " are allowed - choose a shorter period or fewer teachers")));
	}

	public static ReportValidationException tooManyEntries(long entries, long max) {
		return new ReportValidationException(REPORT_TOO_LARGE, "The report is too large",
				List.of(new FieldError("period", "covers " + entries + " ledger entries; at most " + max
						+ " are allowed - choose a shorter period or fewer teachers")));
	}

	public static ReportValidationException snapshotTooLarge(int bytes, int max) {
		return snapshotTooLarge(bytes, max, "choose a shorter period or fewer teachers");
	}

	public static ReportValidationException snapshotTooLarge(int bytes, int max, String hint) {
		return new ReportValidationException(REPORT_TOO_LARGE, "The report is too large",
				List.of(new FieldError("period", "the report result is " + bytes + " bytes; at most " + max
						+ " are allowed - " + hint)));
	}

	/** RPT-02: a {@code 422 REPORT_TOO_LARGE} with one field error and a report-specific hint. */
	public static ReportValidationException tooLarge(String field, String detail) {
		return new ReportValidationException(REPORT_TOO_LARGE, "The report is too large",
				List.of(new FieldError(field, detail)));
	}

}
