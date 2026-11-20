package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-tenant device settings (V64, Wave 10). At most one row per tenant; a
 * tenant with no row uses the defaults ({@code maxDevices} null = plan
 * default, 24 h cooldown, self-service off).
 */
@Entity
@Table(name = "tenant_device_policy")
public class TenantDevicePolicy extends BaseEntity implements TenantOwned {

	public static final int DEFAULT_RESET_COOLDOWN_HOURS = 24;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "max_devices")
	private Integer maxDevices;

	@Column(name = "reset_cooldown_hours", nullable = false)
	private int resetCooldownHours;

	@Column(name = "student_self_service_enabled", nullable = false)
	private boolean studentSelfServiceEnabled;

	@Column(name = "updated_by", nullable = false)
	private UUID updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TenantDevicePolicy() {
	}

	public TenantDevicePolicy(UUID tenantId) {
		this.tenantId = tenantId;
		this.resetCooldownHours = DEFAULT_RESET_COOLDOWN_HOURS;
	}

	public void update(Integer maxDevices, int resetCooldownHours, boolean studentSelfServiceEnabled, UUID actorId,
			Instant now) {
		this.maxDevices = maxDevices;
		this.resetCooldownHours = resetCooldownHours;
		this.studentSelfServiceEnabled = studentSelfServiceEnabled;
		this.updatedBy = actorId;
		this.updatedAt = now;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public Integer getMaxDevices() {
		return maxDevices;
	}

	public int getResetCooldownHours() {
		return resetCooldownHours;
	}

	public boolean isStudentSelfServiceEnabled() {
		return studentSelfServiceEnabled;
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
