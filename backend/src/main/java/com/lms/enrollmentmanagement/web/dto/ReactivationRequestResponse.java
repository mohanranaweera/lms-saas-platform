package com.lms.enrollmentmanagement.web.dto;

import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import java.time.Instant;
import java.util.UUID;

/** Web-layer response shape for a {@code reactivation_request} row - never the JPA entity. */
public record ReactivationRequestResponse(UUID id, UUID enrollmentId, UUID requestedBy,
		ReactivationRequestStatus status, UUID reviewedBy, Instant reviewedAt, UUID newOrderId, Instant createdAt,
		Instant updatedAt) {

}
