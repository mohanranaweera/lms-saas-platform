package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Explicit teacher permission for a tenant-owned Zoom integration. */
@Entity
@Table(name = "zoom_integration_allowed_teacher")
public class ZoomIntegrationAllowedTeacher extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "integration_id", nullable = false, updatable = false)
	private UUID integrationId;

	@Column(name = "teacher_user_id", nullable = false, updatable = false)
	private UUID teacherUserId;

	protected ZoomIntegrationAllowedTeacher() {
	}

	public ZoomIntegrationAllowedTeacher(UUID tenantId, UUID integrationId, UUID teacherUserId) {
		this.tenantId = tenantId;
		this.integrationId = integrationId;
		this.teacherUserId = teacherUserId;
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

}
