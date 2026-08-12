package com.lms.usermanagement.student.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The operational/profile aspect of a student account, owned by
 * {@code user-management} (MVP-006 Student Management), mapped 1:1 onto
 * {@code student_profile} (V11). The credential aspect (email, password hash,
 * role, status) lives on {@code identity-access-service}'s {@code
 * tenant_user} row instead - {@code userId} below is an opaque foreign key
 * value only, never a JPA {@code @ManyToOne} across the module boundary (per
 * {@code .claude/rules/architecture.md}: a module must never import another
 * domain's {@code domain} classes). Any read/mutation of the credential row
 * goes through {@code identity-access-service}'s {@code api} package.
 */
@Entity
@Table(name = "student_profile")
public class StudentProfile extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "name", nullable = false)
	private String name;

	protected StudentProfile() {
	}

	public StudentProfile(UUID tenantId, UUID userId, String name) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.name = name;
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

	public String getName() {
		return name;
	}

	/**
	 * Updates this profile's own name only - Student Management's
	 * profile-edit endpoint (both the staff-facing edit and the self-service
	 * {@code /me} edit) needs a mutator that {@code StaffProfile} did not
	 * require in its first pass. Never touches {@code tenantId}/{@code
	 * userId}, which are immutable identity fields set only at construction.
	 */
	public void rename(String name) {
		this.name = name;
	}

}
