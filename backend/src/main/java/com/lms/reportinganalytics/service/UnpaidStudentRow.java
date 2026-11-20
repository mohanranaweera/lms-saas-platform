package com.lms.reportinganalytics.service;

import java.util.UUID;

/** One complete stored Unpaid Students result row. */
public record UnpaidStudentRow(UUID studentProfileId, UUID studentId, String indexNumber, String studentName,
		String email, String mobile, int attendanceCount) {

}
