package com.lms.attendancemanagement.web.dto;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.util.UUID;

/** {@code { studentId, status }} - {@code status} is {@code null} when the student has not yet been marked. */
public record AttendanceRosterEntryResponse(UUID studentId, AttendanceStatus status) {

}
