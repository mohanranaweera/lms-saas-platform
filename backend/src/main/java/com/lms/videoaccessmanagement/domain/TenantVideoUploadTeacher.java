package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A teacher allowed to upload to the institute YouTube channel
 * ({@code tenant_video_upload_teacher}, V73 - VIDEO Phase 5b). No rows for a
 * tenant means every teacher may upload.
 */
@Entity
@Table(name = "tenant_video_upload_teacher")
public class TenantVideoUploadTeacher extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_user_id", nullable = false, updatable = false)
	private UUID teacherUserId;

	protected TenantVideoUploadTeacher() {
	}

	public TenantVideoUploadTeacher(UUID tenantId, UUID teacherUserId) {
		this.tenantId = tenantId;
		this.teacherUserId = teacherUserId;
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
