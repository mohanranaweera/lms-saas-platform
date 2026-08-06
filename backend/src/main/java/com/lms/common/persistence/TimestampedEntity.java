package com.lms.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base type for entities that carry {@code created_at}/{@code updated_at}
 * timestamps but no {@code created_by}/{@code updated_by} actor columns -
 * unlike {@link Auditable}, which requires both. Added for
 * identity-access-service/tenant-management's {@code tenant}/{@code
 * tenant_user}/{@code platform_admin_user} tables (V2-V4), whose already
 * -shared migrations only define timestamp columns: mapping those entities
 * onto {@link Auditable} instead would fail Hibernate schema validation
 * against those migrations (a missing {@code created_by}/{@code updated_by}
 * column), and the migrations are the source of truth here, not a
 * package-layout convention written before the schema existed.
 *
 * <p>Prefer {@link Auditable} whenever a table genuinely has actor columns;
 * this type exists specifically for the timestamp-only case so future
 * modules facing the same shape do not have to re-derive this pattern.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class TimestampedEntity extends BaseEntity {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
