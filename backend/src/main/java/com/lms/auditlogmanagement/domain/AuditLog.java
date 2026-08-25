package com.lms.auditlogmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapped 1:1 onto {@code audit_log} (V21) - the minimal, durable
 * {@code audit-log-management} forward-pull approved for MVP-011 (product
 * owner decision 1: a real write target only, no read/query API, no
 * consumption of other domains' events). Immutable once written - no setter
 * exists for any field beyond construction, and {@link AuditLogRepository}
 * exposes no update/delete method, per {@code .claude/rules/security.md}'s
 * "Audit logs are themselves ... append-only" rule.
 *
 * <p>{@code metadata} is stored as raw JSON text against the table's
 * {@code jsonb} column via Hibernate's native JSON mapping ({@link
 * JdbcTypeCode}) - callers (see {@code AuditLogService}) are responsible for
 * serializing a structured payload (e.g. which duplicate flags were
 * overridden) to a JSON string before construction; this entity does not
 * itself perform JSON serialization.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "actor_id", nullable = false, updatable = false)
	private UUID actorId;

	@Column(name = "action", nullable = false, updatable = false, length = 100)
	private String action;

	@Column(name = "target_entity", nullable = false, updatable = false, length = 100)
	private String targetEntity;

	@Column(name = "target_id", nullable = false, updatable = false)
	private UUID targetId;

	@Column(name = "reason", updatable = false)
	private String reason;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata", updatable = false)
	private String metadata;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected AuditLog() {
	}

	public AuditLog(UUID tenantId, UUID actorId, String action, String targetEntity, UUID targetId, String reason,
			String metadata, Instant occurredAt) {
		this.tenantId = tenantId;
		this.actorId = actorId;
		this.action = action;
		this.targetEntity = targetEntity;
		this.targetId = targetId;
		this.reason = reason;
		this.metadata = metadata;
		this.occurredAt = occurredAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getActorId() {
		return actorId;
	}

	public String getAction() {
		return action;
	}

	public String getTargetEntity() {
		return targetEntity;
	}

	public UUID getTargetId() {
		return targetId;
	}

	public String getReason() {
		return reason;
	}

	public String getMetadata() {
		return metadata;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

}
