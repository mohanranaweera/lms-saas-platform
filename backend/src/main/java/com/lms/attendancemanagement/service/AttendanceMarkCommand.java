package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.util.UUID;

/** One roster row submitted to {@link AttendanceMarkingService#markAttendance}. */
public record AttendanceMarkCommand(UUID studentId, AttendanceStatus status) {

}
