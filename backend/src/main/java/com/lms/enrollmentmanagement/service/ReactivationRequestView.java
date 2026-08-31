package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import java.time.Instant;
import java.util.UUID;

/** Service-layer read shape for a {@code reactivation_request} row - never the JPA entity itself. */
public record ReactivationRequestView(UUID id, UUID enrollmentId, UUID requestedBy, ReactivationRequestStatus status,
		UUID reviewedBy, Instant reviewedAt, UUID newOrderId, Instant createdAt, Instant updatedAt) {

}
