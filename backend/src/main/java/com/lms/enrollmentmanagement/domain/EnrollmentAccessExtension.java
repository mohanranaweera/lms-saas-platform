package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A manual access extension (V65, Wave 10, ADR-022). Append-only and fully
 * immutable: every column is {@code updatable = false}, the repository
 * forbids deletes and a DB trigger rejects UPDATE/DELETE. {@code
 * enrollment.access_expires_at} is never changed - the effective expiry is
 * the later of that column and the newest {@code newExpiresAt}.
 */
@Entity
@Table(name = "enrollment_access_extension")
public class EnrollmentAccessExtension extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "enrollment_id", nullable = false, updatable = false)
	private UUID enrollmentId;

	@Column(name = "previous_expires_at", nullable = false, updatable = false)
	private Instant previousExpiresAt;

	@Column(name = "new_expires_at", nullable = false, updatable = false)
	private Instant newExpiresAt;

	@Column(name = "reason", nullable = false, updatable = false)
	private String reason;

	@Column(name = "granted_by", nullable = false, updatable = false)
	private UUID grantedBy;

	@Column(name = "granted_at", nullable = false, updatable = false)
	private Instant grantedAt;

	protected EnrollmentAccessExtension() {
	}

	public EnrollmentAccessExtension(UUID tenantId, UUID enrollmentId, Instant previousExpiresAt,
			Instant newExpiresAt, String reason, UUID grantedBy, Instant grantedAt) {
		if (!newExpiresAt.isAfter(previousExpiresAt)) {
			throw new IllegalArgumentException("newExpiresAt must be after previousExpiresAt");
		}
		this.tenantId = tenantId;
		this.enrollmentId = enrollmentId;
		this.previousExpiresAt = previousExpiresAt;
		this.newExpiresAt = newExpiresAt;
		this.reason = reason;
		this.grantedBy = grantedBy;
		this.grantedAt = grantedAt;
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

	public Instant getPreviousExpiresAt() {
		return previousExpiresAt;
	}

	public Instant getNewExpiresAt() {
		return newExpiresAt;
	}

	public String getReason() {
		return reason;
	}

	public UUID getGrantedBy() {
		return grantedBy;
	}

	public Instant getGrantedAt() {
		return grantedAt;
	}

}
