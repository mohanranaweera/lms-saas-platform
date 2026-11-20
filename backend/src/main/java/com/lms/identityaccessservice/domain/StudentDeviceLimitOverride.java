package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Student-level device-limit exception (V64) - the most specific level of the
 * device-limit precedence (student, course, tenant, plan). Configuration,
 * not history: clearing it deletes the row (the change is audit-logged).
 */
@Entity
@Table(name = "student_device_limit_override")
public class StudentDeviceLimitOverride extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "max_devices", nullable = false)
	private int maxDevices;

	@Column(name = "reason", nullable = false)
	private String reason;

	@Column(name = "set_by", nullable = false)
	private UUID setBy;

	@Column(name = "set_at", nullable = false)
	private Instant setAt;

	protected StudentDeviceLimitOverride() {
	}

	public StudentDeviceLimitOverride(UUID tenantId, UUID userId) {
		this.tenantId = tenantId;
		this.userId = userId;
	}

	public void set(int maxDevices, String reason, UUID actorId, Instant now) {
		this.maxDevices = maxDevices;
		this.reason = reason;
		this.setBy = actorId;
		this.setAt = now;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getUserId() {
		return userId;
	}

	public int getMaxDevices() {
		return maxDevices;
	}

	public String getReason() {
		return reason;
	}

	public UUID getSetBy() {
		return setBy;
	}

	public Instant getSetAt() {
		return setAt;
	}

}
