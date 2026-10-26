package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/** Immutable Student Behavior report header; complete rows stay in owner-bound snapshot. */
public record StudentBehaviorReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
        Institute institute, CourseRef course, Filters filters, Classification classification,
        Counts counts) {

    public StudentBehaviorReport withRun(UUID id, Instant expires) {
        return new StudentBehaviorReport(id, expires, generatedAt, timezone, institute, course, filters,
                classification, counts);
    }

    public record Institute(String name) { }
    public record CourseRef(UUID id, String name) { }
    public record Filters(YearMonth billingMonth, String billingMonthLabel) { }
    public record Classification(String basis, YearMonth comparisonMonth, String historicalAvailability,
            boolean provisional, Instant asOf) { }
    public record Counts(int newStudents, int droppedStudents, int continuingStudents) {
        public int totalRows() { return newStudents + droppedStudents + continuingStudents; }
    }
}
