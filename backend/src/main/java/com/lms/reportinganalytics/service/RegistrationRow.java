package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One tenant student registration. Identity fields remain strings where leading zeroes matter. */
public record RegistrationRow(UUID studentProfileId, UUID studentId, String indexNumber, String studentName,
		String email, String mobile, UUID registeredById, String registeredBy, String registrationMethod,
		List<RegistrationReport.CourseRef> courses, String lastPayment, Instant registrationDate) {

	public RegistrationRow {
		courses = courses == null ? List.of() : List.copyOf(courses);
	}
}
