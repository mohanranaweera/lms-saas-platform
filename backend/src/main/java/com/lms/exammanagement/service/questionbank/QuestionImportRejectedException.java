package com.lms.exammanagement.service.questionbank;

import com.lms.common.error.ApplicationException;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ImportError;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 422 {@code QUESTION_IMPORT_INVALID}: the CSV was rejected as a whole and nothing was
 * imported. Rendered by {@code GlobalExceptionHandler} as {@code error.details.errors =
 * [ { row, column, message } ]}.
 */
public class QuestionImportRejectedException extends ApplicationException {

	public static final String ERROR_CODE = "QUESTION_IMPORT_INVALID";

	/** At most this many problems are reported per file. */
	public static final int MAX_REPORTED_ERRORS = 100;

	private final List<ImportError> errors;

	public QuestionImportRejectedException(List<ImportError> errors) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE, "The file was not imported. Fix the problems and try again.");
		this.errors = List.copyOf(errors.size() > MAX_REPORTED_ERRORS ? errors.subList(0, MAX_REPORTED_ERRORS) : errors);
	}

	public static QuestionImportRejectedException fileProblem(String message) {
		return new QuestionImportRejectedException(List.of(new ImportError(null, null, message)));
	}

	public List<ImportError> getErrors() {
		return errors;
	}

	@Override
	public Map<String, Object> getDetails() {
		return Map.of("errors", errors);
	}

}
