package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Immutable Registration Report run header. */
public record RegistrationReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, Filters filters, CurrentFacts currentFacts, int totalRegistrations) {

	public RegistrationReport withRun(UUID id, Instant expires) {
		return new RegistrationReport(id, expires, generatedAt, timezone, institute, filters, currentFacts,
				totalRegistrations);
	}

	public record Institute(String name) { }
	public record CourseRef(UUID id, String name) { }
	public record OperatorRef(UUID id, String name) { }
	public record Filters(List<CourseRef> courses, OperatorRef operator, String registrationMethod,
			LocalDate startDate, LocalDate endDate, int selectedDayCount) {
		public Filters { courses = courses == null ? List.of() : List.copyOf(courses); }
	}
	public record CurrentFacts(String courseAssociationBasis, String lastPaymentDefinition, Instant asOf) { }
}
