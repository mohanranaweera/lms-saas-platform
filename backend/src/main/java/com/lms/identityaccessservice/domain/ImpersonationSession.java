package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * ADR-033 support ("Log as Student") session, mapped onto {@code impersonation_session}
 * (V117). Rows are never deleted; ending a session stamps {@code ended_at}/{@code
 * end_reason} once. The raw handoff secret is never stored - only its SHA-256.
 */
@Entity
@Table(name = "impersonation_session")
public class ImpersonationSession extends BaseEntity implements TenantOwned {

	public enum EndReason {
		EXITED, REPLACED, EXPIRED, ACTOR_REVOKED
	}

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "actor_user_id", nullable = false, updatable = false)
	private UUID actorUserId;

	@Column(name = "actor_session_id", nullable = false, updatable = false)
	private UUID actorSessionId;

	@Column(name = "subject_user_id", nullable = false, updatable = false)
	private UUID subjectUserId;

	@Column(name = "subject_student_profile_id", nullable = false, updatable = false)
	private UUID subjectStudentProfileId;

	@Column(name = "subject_index_number", updatable = false, length = 20)
	private String subjectIndexNumber;

	@Column(name = "subject_full_name", nullable = false, updatable = false)
	private String subjectFullName;

	@Column(name = "reason", nullable = false, updatable = false, length = 500)
	private String reason;

	@Column(name = "handoff_secret_hash", nullable = false, updatable = false, length = 64)
	private String handoffSecretHash;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "end_reason", length = 20)
	private EndReason endReason;

	protected ImpersonationSession() {
	}

	public ImpersonationSession(UUID tenantId, UUID actorUserId, UUID actorSessionId, UUID subjectUserId,
			UUID subjectStudentProfileId, String subjectIndexNumber, String subjectFullName, String reason,
			String handoffSecretHash, Instant startedAt, Instant expiresAt) {
		this.tenantId = tenantId;
		this.actorUserId = actorUserId;
		this.actorSessionId = actorSessionId;
		this.subjectUserId = subjectUserId;
		this.subjectStudentProfileId = subjectStudentProfileId;
		this.subjectIndexNumber = subjectIndexNumber;
		this.subjectFullName = subjectFullName;
		this.reason = reason;
		this.handoffSecretHash = handoffSecretHash;
		this.startedAt = startedAt;
		this.expiresAt = expiresAt;
	}

	/** Open (not ended) and not past its absolute expiry. */
	public boolean isActive(Instant now) {
		return endedAt == null && now.isBefore(expiresAt);
	}

	public boolean isEnded() {
		return endedAt != null;
	}

	/** Ends the session once; a second call is a no-op. */
	public boolean end(EndReason reason, Instant now) {
		if (endedAt != null) {
			return false;
		}
		this.endedAt = now;
		this.endReason = reason;
		return true;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public UUID getActorSessionId() {
		return actorSessionId;
	}

	public UUID getSubjectUserId() {
		return subjectUserId;
	}

	public UUID getSubjectStudentProfileId() {
		return subjectStudentProfileId;
	}

	public String getSubjectIndexNumber() {
		return subjectIndexNumber;
	}

	public String getSubjectFullName() {
		return subjectFullName;
	}

	public String getReason() {
		return reason;
	}

	public String getHandoffSecretHash() {
		return handoffSecretHash;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public EndReason getEndReason() {
		return endReason;
	}

}
