package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A teacher allowed to upload to one integration ({@code
 * tenant_media_integration_teacher}, V76 - VIDEO Phase 5d). No rows for an
 * integration means every teacher may upload to it.
 */
@Entity
@Table(name = "tenant_media_integration_teacher")
public class TenantMediaIntegrationTeacher extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "integration_id", nullable = false, updatable = false)
	private UUID integrationId;

	@Column(name = "teacher_user_id", nullable = false, updatable = false)
	private UUID teacherUserId;

	protected TenantMediaIntegrationTeacher() {
	}

	public TenantMediaIntegrationTeacher(UUID tenantId, UUID integrationId, UUID teacherUserId) {
		this.tenantId = tenantId;
		this.integrationId = integrationId;
		this.teacherUserId = teacherUserId;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public UUID getTeacherUserId() {
		return teacherUserId;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

}
