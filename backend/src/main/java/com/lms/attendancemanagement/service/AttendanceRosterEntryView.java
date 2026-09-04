package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.util.UUID;

/** One roster row for the Mark Attendance screen. {@code status} is {@code null} when not yet marked. */
public record AttendanceRosterEntryView(UUID studentId, AttendanceStatus status) {

}
