package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-owned named Zoom integration foundation for the MAPPED_TEACHER_HOST strategy.
 * OAuth credentials are deliberately owned by a later connection flow and are not stored here.
 */
@Entity
@Table(name = "zoom_integration")
public class ZoomIntegration extends Auditable implements TenantOwned {

	private static final int MAX_ERROR_LENGTH = 500;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ZoomIntegrationStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "teacher_policy", nullable = false, length = 20)
	private ZoomTeacherPolicy teacherPolicy;

	@Enumerated(EnumType.STRING)
	@Column(name = "host_strategy", nullable = false, length = 32, updatable = false)
	private ZoomHostStrategy hostStrategy;

	@Enumerated(EnumType.STRING)
	@Column(name = "readiness", nullable = false, length = 20)
	private ZoomReadiness readiness;

	@Column(name = "zoom_account_id", length = 255)
	private String zoomAccountId;

	@Column(name = "zoom_account_label", length = 255)
	private String zoomAccountLabel;

	@Column(name = "connected_at")
	private Instant connectedAt;

	@Column(name = "last_verified_at")
	private Instant lastVerifiedAt;

	@Column(name = "last_error", length = MAX_ERROR_LENGTH)
	private String lastError;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	@Version
	@Column(name = "version", nullable = false)
	private int version;

	protected ZoomIntegration() {
	}

	public ZoomIntegration(UUID tenantId, String name, String description, ZoomTeacherPolicy teacherPolicy) {
		this.tenantId = tenantId;
		this.name = name;
		this.description = description;
		this.status = ZoomIntegrationStatus.CREATED;
		this.teacherPolicy = teacherPolicy;
		this.hostStrategy = ZoomHostStrategy.MAPPED_TEACHER_HOST;
		this.readiness = ZoomReadiness.UNVALIDATED;
	}

	public void updateDetails(String name, String description, ZoomTeacherPolicy teacherPolicy) {
		requireNotDeleted();
		this.name = name;
		this.description = description;
		this.teacherPolicy = teacherPolicy;
	}

	public void markConnecting() {
		requireNotDeleted();
		this.status = ZoomIntegrationStatus.CONNECTING;
	}

	public void markConnected(String accountId, String accountLabel, Instant connectedAt) {
		requireNotDeleted();
		this.status = ZoomIntegrationStatus.CONNECTED;
		this.zoomAccountId = accountId;
		this.zoomAccountLabel = accountLabel;
		this.connectedAt = connectedAt;
		this.lastError = null;
	}

	public void markNeedsReconnect(ZoomIntegrationStatus status, String error) {
		requireNotDeleted();
		if (status != ZoomIntegrationStatus.TOKEN_EXPIRED && status != ZoomIntegrationStatus.AUTHORIZATION_REVOKED
				&& status != ZoomIntegrationStatus.ERROR) {
			throw new IllegalArgumentException("Invalid reconnect status: " + status);
		}
		this.status = status;
		this.lastError = truncate(error);
	}

	public void markValidated(Instant verifiedAt) {
		requireNotDeleted();
		this.readiness = ZoomReadiness.VALIDATED;
		this.lastVerifiedAt = verifiedAt;
		this.lastError = null;
	}

	public void markBlocked(String error) {
		requireNotDeleted();
		this.readiness = ZoomReadiness.BLOCKED;
		this.lastError = truncate(error);
	}

	public void archive(UUID actorId, Instant at) {
		if (status == ZoomIntegrationStatus.DELETED) {
			throw new IllegalStateException("Zoom integration is already deleted");
		}
		this.status = ZoomIntegrationStatus.DELETED;
		this.deletedAt = at;
		this.deletedBy = actorId;
	}

	public boolean isActive() {
		return status != ZoomIntegrationStatus.DELETED;
	}

	private void requireNotDeleted() {
		if (!isActive()) {
			throw new IllegalStateException("Zoom integration is deleted");
		}
	}

	private static String truncate(String value) {
		if (value == null || value.length() <= MAX_ERROR_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_ERROR_LENGTH);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public ZoomIntegrationStatus getStatus() {
		return status;
	}

	public ZoomTeacherPolicy getTeacherPolicy() {
		return teacherPolicy;
	}

	public ZoomHostStrategy getHostStrategy() {
		return hostStrategy;
	}

	public ZoomReadiness getReadiness() {
		return readiness;
	}

	public String getZoomAccountId() {
		return zoomAccountId;
	}

	public String getZoomAccountLabel() {
		return zoomAccountLabel;
	}

	public Instant getConnectedAt() {
		return connectedAt;
	}

	public Instant getLastVerifiedAt() {
		return lastVerifiedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public UUID getDeletedBy() {
		return deletedBy;
	}

}
