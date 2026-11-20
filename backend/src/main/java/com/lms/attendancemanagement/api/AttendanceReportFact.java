package com.lms.attendancemanagement.api;

import java.time.Instant;
import java.util.UUID;

/** One canonical student/session attendance fact exposed for reporting. */
public record AttendanceReportFact(UUID recordId, UUID studentId, String status, UUID sheetId, Instant occurredAt) {

}
