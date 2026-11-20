package com.lms.reportinganalytics.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UndoPaymentReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, Filters filters, int rowCount, int distinctUndoPayments, BigDecimal totalAmount) {
	public record Institute(String name) { }
	public record CourseRef(UUID id, String name) { }
	public record Filters(List<CourseRef> courses, LocalDate startDate, LocalDate endDate, int selectedDayCount) {
		public Filters { courses = courses == null ? List.of() : List.copyOf(courses); }
	}
}
