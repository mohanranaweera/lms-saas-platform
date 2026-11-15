package com.lms.financeexpensemanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * FIN-01: a tenant finance account (Cash, Bank, Card ...). Holds no balance:
 * the balance is derived from {@link FinanceTransaction} rows. The row is the
 * per-account lock for balance adjustments and archiving. Archived, never
 * deleted (postings reference it).
 */
@Entity
@Table(name = "finance_account")
public class FinanceAccount extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "archived", nullable = false)
	private boolean archived;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "archived_by")
	private UUID archivedBy;

	protected FinanceAccount() {
	}

	public FinanceAccount(UUID tenantId, String name, String description, String currency, UUID createdBy) {
		this.tenantId = tenantId;
		this.name = name;
		this.description = description;
		this.currency = currency;
		this.createdBy = createdBy;
	}

	public void rename(String name, String description, UUID actorId) {
		this.name = name;
		this.description = description;
		this.updatedBy = actorId;
	}

	public void archive(UUID actorId, Instant at) {
		this.archived = true;
		this.archivedAt = at;
		this.archivedBy = actorId;
		this.updatedBy = actorId;
	}

	public void unarchive(UUID actorId) {
		this.archived = false;
		this.archivedAt = null;
		this.archivedBy = null;
		this.updatedBy = actorId;
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

	public String getCurrency() {
		return currency;
	}

	public boolean isArchived() {
		return archived;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getArchivedAt() {
		return archivedAt;
	}

}
