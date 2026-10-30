package com.lms.exammanagement.service.exam;

import com.lms.common.error.ApplicationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 409 {@value #ERROR_CODE}: the request would change something that is frozen because the
 * exam has been attempted (structural fields, the paper) or would delete an attempted exam.
 * {@code details.fields} names the locked fields the request tried to change.
 */
public class ExamLockedException extends ApplicationException {

	public static final String ERROR_CODE = "EXAM_LOCKED";

	private final List<String> fields;

	public ExamLockedException(String message, List<String> fields) {
		super(HttpStatus.CONFLICT, ERROR_CODE, message);
		this.fields = List.copyOf(fields);
	}

	@Override
	public Map<String, Object> getDetails() {
		return fields.isEmpty() ? null : Map.of("fields", fields);
	}

}
