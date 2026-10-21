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

/** Maps an LMS teacher to one synchronized Zoom user for one tenant integration. */
@Entity
@Table(name = "zoom_teacher_user_mapping")
public class ZoomTeacherUserMapping extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "integration_id", nullable = false, updatable = false)
	private UUID integrationId;

	@Column(name = "teacher_user_id", nullable = false, updatable = false)
	private UUID teacherUserId;

	@Column(name = "zoom_external_user_id", nullable = false, updatable = false)
	private UUID zoomExternalUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private ZoomMappingStatus status;

	@Column(name = "mapped_at", nullable = false, updatable = false)
	private Instant mappedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected ZoomTeacherUserMapping() {
	}

	public ZoomTeacherUserMapping(UUID tenantId, UUID integrationId, UUID teacherUserId, UUID zoomExternalUserId,
			Instant mappedAt) {
		this.tenantId = tenantId;
		this.integrationId = integrationId;
		this.teacherUserId = teacherUserId;
		this.zoomExternalUserId = zoomExternalUserId;
		this.status = ZoomMappingStatus.ACTIVE;
		this.mappedAt = mappedAt;
	}

	public void revoke(Instant at) {
		if (status == ZoomMappingStatus.REVOKED) {
			throw new IllegalStateException("Zoom teacher mapping is already revoked");
		}
		this.status = ZoomMappingStatus.REVOKED;
		this.revokedAt = at;
	}

	public boolean isActive() {
		return status == ZoomMappingStatus.ACTIVE;
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

	public UUID getTeacherUserId() {
		return teacherUserId;
	}

	public UUID getZoomExternalUserId() {
		return zoomExternalUserId;
	}

	public ZoomMappingStatus getStatus() {
		return status;
	}

	public Instant getMappedAt() {
		return mappedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

}
