package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Non-secret, tenant-scoped snapshot of a Zoom user synchronized for an integration. */
@Entity
@Table(name = "zoom_external_user")
public class ZoomExternalUser extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "integration_id", nullable = false, updatable = false)
	private UUID integrationId;

	@Column(name = "zoom_user_id", nullable = false, updatable = false, length = 255)
	private String zoomUserId;

	@Column(name = "email", length = 320)
	private String email;

	@Column(name = "display_name", length = 255)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ZoomExternalUserStatus status;

	@Column(name = "zoom_user_type")
	private Integer zoomUserType;

	@Enumerated(EnumType.STRING)
	@Column(name = "host_capability", nullable = false, length = 20)
	private ZoomCapability hostCapability;

	@Enumerated(EnumType.STRING)
	@Column(name = "webinar_capability", nullable = false, length = 20)
	private ZoomCapability webinarCapability;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@Column(name = "capability_checked_at")
	private Instant capabilityCheckedAt;

	protected ZoomExternalUser() {
	}

	public ZoomExternalUser(UUID tenantId, UUID integrationId, String zoomUserId, String email, String displayName,
			ZoomExternalUserStatus status, Integer zoomUserType, ZoomCapability hostCapability,
			ZoomCapability webinarCapability, Instant lastSyncedAt) {
		this.tenantId = tenantId;
		this.integrationId = integrationId;
		this.zoomUserId = requireText(zoomUserId, "zoomUserId");
		this.email = email;
		this.displayName = displayName;
		this.status = status;
		this.zoomUserType = zoomUserType;
		this.hostCapability = hostCapability;
		this.webinarCapability = webinarCapability;
		this.lastSyncedAt = lastSyncedAt;
	}

	public void refreshSnapshot(String email, String displayName, ZoomExternalUserStatus status, Integer zoomUserType,
			ZoomCapability hostCapability, ZoomCapability webinarCapability, Instant syncedAt, Instant capabilityCheckedAt) {
		this.email = email;
		this.displayName = displayName;
		this.status = status;
		this.zoomUserType = zoomUserType;
		this.hostCapability = hostCapability;
		this.webinarCapability = webinarCapability;
		this.lastSyncedAt = syncedAt;
		this.capabilityCheckedAt = capabilityCheckedAt;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public String getZoomUserId() {
		return zoomUserId;
	}

	public String getEmail() {
		return email;
	}

	public String getDisplayName() {
		return displayName;
	}

	public ZoomExternalUserStatus getStatus() {
		return status;
	}

	public Integer getZoomUserType() {
		return zoomUserType;
	}

	public ZoomCapability getHostCapability() {
		return hostCapability;
	}

	public ZoomCapability getWebinarCapability() {
		return webinarCapability;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public Instant getCapabilityCheckedAt() {
		return capabilityCheckedAt;
	}

}
