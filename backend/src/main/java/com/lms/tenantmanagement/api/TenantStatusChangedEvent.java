package com.lms.tenantmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code TenantApprovalService} on every Platform-Admin-driven
 * tenant status transition (approve/reject in this slice), inside the same
 * transaction that persists the status change via {@code
 * Tenant#applyStatusTransition}. Consumed synchronously by {@code
 * audit-log-management}'s {@code AuditLogEventListener}, mirroring the same
 * pattern already established for {@code CoursePriceChangedEvent}/{@code
 * MaterialDeletedEvent} - a listener exception propagates back through {@code
 * ApplicationEventPublisher.publishEvent} and rolls back the source status
 * change, so "status flip + exactly one audit row" stays atomic.
 *
 * <p>{@code actorId} is the Platform Admin who performed the action,
 * resolved from {@code AuthenticatedPrincipalHolder} - never a
 * caller-supplied value.
 */
public record TenantStatusChangedEvent(UUID tenantId, UUID actorId, TenantStatus previousStatus,
		TenantStatus newStatus, Instant occurredAt) {

}
