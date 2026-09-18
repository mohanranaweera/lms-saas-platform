package com.lms.tenantmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapped 1:1 onto {@code tenant_config_entry} (V36) - the current-value row
 * for one (tenant, config domain, config key) triple in the typed tenant
 * configuration framework (Wave 1). Lives in {@code tenant-management}'s
 * existing package rather than a new top-level domain, per
 * {@code .claude/rules/architecture.md}'s confirmed domain list (no
 * {@code tenant-configuration-management} entry - this is an extension of
 * the {@code Tenant} aggregate).
 *
 * <p>{@code value} is stored as raw JSON text against the table's
 * {@code jsonb} column via Hibernate's native JSON mapping ({@link
 * JdbcTypeCode}), mirroring {@code AuditLog#metadata}/{@code
 * NotificationOutbox#payload}'s exact pattern - {@code TenantConfigService}
 * is responsible for serializing/deserializing the scalar value to/from a
 * JSON string; this entity does not itself perform JSON
 * serialization.
 *
 * <p>Not append-only (unlike {@code .claude/rules/backend.md}'s named
 * append-only domains) - {@link #updateValue(String)} updates the row
 * in place, since this is current-state tenant settings, not a
 * financial/audit trail; the who/when/before/after history of a change is
 * captured separately via {@code audit-log-management}, not by versioning
 * this row.
 */
@Entity
@Table(name = "tenant_config_entry")
public class TenantConfigEntry extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "config_domain", nullable = false, updatable = false, length = 32)
	private String configDomain;

	@Column(name = "config_key", nullable = false, updatable = false, length = 100)
	private String configKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "value", nullable = false)
	private String value;

	protected TenantConfigEntry() {
	}

	public TenantConfigEntry(UUID tenantId, String configDomain, String configKey, String value) {
		this.tenantId = tenantId;
		this.configDomain = configDomain;
		this.configKey = configKey;
		this.value = value;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getConfigDomain() {
		return configDomain;
	}

	public String getConfigKey() {
		return configKey;
	}

	public String getValue() {
		return value;
	}

	/** The one legitimate in-place mutation - a new value for an already-existing (tenant, domain, key) row. */
	public void updateValue(String value) {
		this.value = value;
	}

}
