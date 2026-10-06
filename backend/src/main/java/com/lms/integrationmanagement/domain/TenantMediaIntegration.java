package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.integrationmanagement.api.MediaProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A named institute video integration - a Klass-style card ({@code
 * tenant_media_integration}, V76 - VIDEO Phase 5d, ADR-018 Amendment 5 item
 * 2). Exists before it is connected; holds at most one live connection.
 * Deleting it is a status change: its connections and videos stay as history.
 */
@Entity
@Table(name = "tenant_media_integration")
public class TenantMediaIntegration extends Auditable implements TenantOwned {

	public enum Status {

		ACTIVE, DELETED

	}

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	private MediaProvider provider;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	protected TenantMediaIntegration() {
	}

	public TenantMediaIntegration(UUID tenantId, MediaProvider provider, String name, String description) {
		this.tenantId = tenantId;
		this.provider = provider;
		this.name = name;
		this.description = description;
		this.status = Status.ACTIVE;
	}

	public void rename(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public void markDeleted(UUID actorId, Instant now) {
		this.status = Status.DELETED;
		this.deletedAt = now;
		this.deletedBy = actorId;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	public MediaProvider getProvider() {
		return provider;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getDeletedAt() {
		return deletedAt;
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
