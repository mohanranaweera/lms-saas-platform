package com.lms.exammanagement.web.dto;

import java.util.UUID;

/**
 * {@code PUT /api/v1/student/attempts/{attemptId}/items/{itemId}}: the selected option ({@code
 * null} clears the answer) and the client's monotonic save counter ({@code >= 1}).
 */
public record SaveAttemptItemRequest(UUID optionId, Long revision) {

}
