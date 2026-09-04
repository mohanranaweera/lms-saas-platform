package com.lms.attendancemanagement.service;

import java.util.List;
import java.util.UUID;

/** Roster + existing marks for one session, per plan §10's {@code GET .../roster} response shape. */
public record AttendanceRosterView(UUID courseId, UUID sessionId, List<AttendanceRosterEntryView> roster) {

}
