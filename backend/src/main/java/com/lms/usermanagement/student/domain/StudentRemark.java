package com.lms.usermanagement.student.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * STU-MGMT-02 staff-only internal remark on a student ({@code student_remark}, V117).
 * Plain text; immutable once written (no setters, no update/delete path).
 */
@Entity
@Table(name = "student_remark")
public class StudentRemark extends BaseEntity implements TenantOwned {

	public static final int MAX_BODY_LENGTH = 2000;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_profile_id", nullable = false, updatable = false)
	private UUID studentProfileId;

	@Column(name = "body", nullable = false, updatable = false, length = MAX_BODY_LENGTH)
	private String body;

	@Column(name = "author_user_id", nullable = false, updatable = false)
	private UUID authorUserId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StudentRemark() {
	}

	public StudentRemark(UUID tenantId, UUID studentProfileId, String body, UUID authorUserId, Instant createdAt) {
		this.tenantId = tenantId;
		this.studentProfileId = studentProfileId;
		this.body = body;
		this.authorUserId = authorUserId;
		this.createdAt = createdAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStudentProfileId() {
		return studentProfileId;
	}

	public String getBody() {
		return body;
	}

	public UUID getAuthorUserId() {
		return authorUserId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
