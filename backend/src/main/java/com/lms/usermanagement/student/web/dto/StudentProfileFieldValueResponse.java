package com.lms.usermanagement.student.web.dto;

/** One enabled student profile field and its value (STU-PROF-01). */
public record StudentProfileFieldValueResponse(String key, String label, String group, String type, int maxLength,
		boolean sensitive, boolean required, Object value) {

}
