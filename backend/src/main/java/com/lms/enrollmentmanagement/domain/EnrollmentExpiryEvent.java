package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code enrollment_expiry_event} (V22, ENR-2). Fully
 * append-only, one row per (enrollment, event_type) - {@code
 * uq_enrollment_expiry_event_tenant_enrollment_type} is the real,
 * schema-enforced idempotency guarantee; {@code EnrollmentExpiryService}'s
 * existence check is the friendly pre-check. Never a mutation of the {@code
 * enrollment} row it concerns, and never itself an audit-log entry (plan
 * §16 - a system-detected transition with no human actor, structurally
 * incompatible with {@code audit_log.actor_id}'s {@code NOT NULL} FK).
 */
@Entity
@Table(name = "enrollment_expiry_event")
public class EnrollmentExpiryEvent extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "enrollment_id", nullable = false, updatable = false)
	private UUID enrollmentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false, length = 20)
	private EnrollmentExpiryEventType eventType;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected EnrollmentExpiryEvent() {
	}

	private EnrollmentExpiryEvent(UUID tenantId, UUID enrollmentId, EnrollmentExpiryEventType eventType) {
		this.tenantId = tenantId;
		this.enrollmentId = enrollmentId;
		this.eventType = eventType;
		this.occurredAt = Instant.now();
	}

	/** The only factory this MVP ships - mirrors {@link EnrollmentExpiryEventType}'s single-value scope. */
	public static EnrollmentExpiryEvent expired(UUID tenantId, UUID enrollmentId) {
		return new EnrollmentExpiryEvent(tenantId, enrollmentId, EnrollmentExpiryEventType.EXPIRED);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getEnrollmentId() {
		return enrollmentId;
	}

	public EnrollmentExpiryEventType getEventType() {
		return eventType;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

}
