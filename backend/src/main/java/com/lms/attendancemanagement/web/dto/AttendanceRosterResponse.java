package com.lms.attendancemanagement.web.dto;

import java.util.List;
import java.util.UUID;

/** {@code { courseId, sessionId, roster: [{ studentId, status }] } } per plan §10's {@code GET .../roster} shape. */
public record AttendanceRosterResponse(UUID courseId, UUID sessionId, List<AttendanceRosterEntryResponse> roster) {

}
