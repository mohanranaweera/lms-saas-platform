package com.lms.tenantmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code TenantRegistrationService} inside the same transaction
 * that writes the new {@code tenant} row (at {@code PENDING_APPROVAL}),
 * mirroring {@code paymentmanagement.api.PaymentConfirmedEvent}'s exact
 * shape/javadoc style. Published so other domains - currently
 * {@code notification-management}, seeding the tenant's default
 * {@code notification_template} rows (MVP-018 plan §21 item 1) - can react to
 * a new tenant existing, without {@code tenant-management} depending back on
 * them (per {@code .claude/rules/architecture.md}: {@code tenant-management}
 * is foundational and must not import a business domain's {@code api}).
 * There is no consumer of this event inside {@code tenant-management} itself
 * - it is fire-and-forget from this module's perspective.
 */
public record TenantRegisteredEvent(UUID tenantId, String tenantName, Instant registeredAt) {

}
