package com.lms.tenantmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped onto {@code student_profile_field_setting} (V59, STU-PROF-01): one
 * tenant's persisted setting for one {@code StudentProfileField} catalogue
 * key. Current-state settings, updated in place; the change history lives in
 * the audit log ({@code student_profile_fields.updated}).
 */
@Entity
@Table(name = "student_profile_field_setting")
public class StudentProfileFieldSetting extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "field_key", nullable = false, updatable = false, length = 64)
	private String fieldKey;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@Column(name = "required", nullable = false)
	private boolean required;

	@Column(name = "collect_at_registration", nullable = false)
	private boolean collectAtRegistration;

	protected StudentProfileFieldSetting() {
	}

	public StudentProfileFieldSetting(UUID tenantId, String fieldKey, boolean enabled, boolean required,
			boolean collectAtRegistration) {
		this.tenantId = tenantId;
		this.fieldKey = fieldKey;
		apply(enabled, required, collectAtRegistration);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getFieldKey() {
		return fieldKey;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isRequired() {
		return required;
	}

	public boolean isCollectAtRegistration() {
		return collectAtRegistration;
	}

	/** Replaces the three flags; {@code required}/{@code collectAtRegistration} require {@code enabled} (V59 CHECKs). */
	public void apply(boolean enabled, boolean required, boolean collectAtRegistration) {
		if ((required || collectAtRegistration) && !enabled) {
			throw new IllegalArgumentException("required/collectAtRegistration require enabled");
		}
		this.enabled = enabled;
		this.required = required;
		this.collectAtRegistration = collectAtRegistration;
	}

}
