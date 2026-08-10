package com.lms.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base type for entities that need standard audit fields. {@code createdBy}/
 * {@code updatedBy} resolve via {@link AuditorAwareImpl}, which delegates to
 * whatever {@link CurrentActorProvider} the application context supplies
 * (identity-access-service's {@code AuthenticatedActorProvider} in the real
 * app) - empty when no authenticated actor exists for the current write
 * (unauthenticated/system writes, or a test slice without that module).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable extends BaseEntity {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@CreatedBy
	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@LastModifiedBy
	@Column(name = "updated_by")
	private UUID updatedBy;

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}

}
