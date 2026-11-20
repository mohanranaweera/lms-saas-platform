package com.lms.localizationmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code user_locale_preference} (V124). The table's primary key is {@code (tenant_id,
 * user_id)}; the entity identifies a row by {@code user_id} alone (a tenant user id belongs to
 * exactly one tenant) so it fits the tenant-aware repository, which adds the tenant predicate
 * to every read.
 */
@Entity
@Table(name = "user_locale_preference")
public class UserLocalePreference implements TenantOwned {

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "locale", nullable = false, length = 16)
	private String locale;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserLocalePreference() {
	}

	public UserLocalePreference(UUID tenantId, UUID userId, String locale, Instant updatedAt) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.locale = locale;
		this.updatedAt = updatedAt;
	}

	public void change(String locale, Instant updatedAt) {
		this.locale = locale;
		this.updatedAt = updatedAt;
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

	public String getLocale() {
		return locale;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
