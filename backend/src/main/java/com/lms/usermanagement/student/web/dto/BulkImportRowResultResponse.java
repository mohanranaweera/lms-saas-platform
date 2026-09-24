package com.lms.usermanagement.student.web.dto;

import java.util.UUID;

/** One row of {@code POST /api/v1/students/bulk-import}'s response body (Wave 3). */
public record BulkImportRowResultResponse(int row, String status, String reason, UUID studentId) {

}
