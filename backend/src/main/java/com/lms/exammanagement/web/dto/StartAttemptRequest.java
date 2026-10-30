package com.lms.exammanagement.web.dto;

/** {@code POST /api/v1/student/exams/{id}/attempts}: the exam password (never logged). */
public record StartAttemptRequest(String password) {

	@Override
	public String toString() {
		return "StartAttemptRequest[password=***]";
	}

}
