package com.lms.exammanagement.service.exam;

import com.lms.common.error.ApplicationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 409 {@value #ERROR_CODE}: the request would move the end of an exam that has already
 * ended or whose results are published - that would reopen the window after answers /
 * marks may have been released. {@code details.fields} names the rejected field(s).
 */
public class ExamEndedException extends ApplicationException {

	public static final String ERROR_CODE = "EXAM_ENDED";

	private final List<String> fields;

	public ExamEndedException(String message, List<String> fields) {
		super(HttpStatus.CONFLICT, ERROR_CODE, message);
		this.fields = List.copyOf(fields);
	}

	@Override
	public Map<String, Object> getDetails() {
		return fields.isEmpty() ? null : Map.of("fields", fields);
	}

}
