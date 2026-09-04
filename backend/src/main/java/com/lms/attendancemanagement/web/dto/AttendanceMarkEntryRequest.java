package com.lms.attendancemanagement.web.dto;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** One roster row within {@link MarkAttendanceRequest#marks()}. */
public record AttendanceMarkEntryRequest(@NotNull UUID studentId, @NotNull AttendanceStatus status) {

}
