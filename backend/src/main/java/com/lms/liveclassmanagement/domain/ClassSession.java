package com.lms.liveclassmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code class_session} (V49) - see that migration's own
 * header for the full tenant-isolation/FK/Path-A rationale.
 * {@code courseId}/{@code lessonId}/{@code teacherId} are opaque cross-domain
 * ids only - never a JPA association across the module boundary (per {@code
 * .claude/rules/architecture.md}), though all are still schema-enforced via
 * V49's composite {@code (tenant_id, ...)} FKs.
 *
 * <p><b>Status transitions</b> ({@link #start}/{@link #complete}/{@link
 * #cancel}) are independent of <b>provisioning outcome</b> ({@link
 * #markProvisioned}/{@link #markProvisioningFailed}) - the two state
 * machines below are deliberately separate, mirroring Wave 4 plan §4's
 * framing: a provider failure never deletes/hides a session, it just stays
 * retryable via {@link #markProvisioningFailed}, independent of whatever its
 * {@link ClassSessionStatus} happens to be.
 */
@Entity
@Table(name = "class_session")
public class ClassSession extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Column(name = "lesson_id", updatable = false)
	private UUID lessonId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "description")
	private String description;

	@Column(name = "scheduled_start", nullable = false)
	private Instant scheduledStart;

	@Column(name = "scheduled_end", nullable = false)
	private Instant scheduledEnd;

	@Column(name = "status", nullable = false, length = 10)
	private ClassSessionStatus status;

	@Column(name = "meeting_provider", nullable = false, updatable = false, length = 10)
	private MeetingProvider meetingProvider;

	@Column(name = "provider_status", nullable = false, length = 12)
	private ClassSessionProviderStatus providerStatus;

	@Column(name = "provider_reference")
	private String providerReference;

	@Column(name = "provider_failure_reason")
	private String providerFailureReason;

	protected ClassSession() {
	}

	/**
	 * The only construction path - a brand-new session, always starting
	 * {@link ClassSessionStatus#SCHEDULED} / {@link
	 * ClassSessionProviderStatus#PENDING} (the pre-provider-call state -
	 * {@code ClassSessionSchedulingService} persists this, commits, THEN
	 * calls the provider, per {@code .claude/rules/backend.md}'s
	 * "never span a transaction across an outbound call" rule). {@code
	 * courseId}/{@code teacherId}/{@code lessonId} MUST already have been
	 * verified server-side by the caller ({@code LiveClassAccessGuard} +
	 * {@code ClassSessionSchedulingService}) - this constructor trusts its
	 * arguments, it does not itself re-derive or validate them.
	 */
	public ClassSession(UUID tenantId, UUID courseId, UUID teacherId, UUID lessonId, String title, String description,
			Instant scheduledStart, Instant scheduledEnd, MeetingProvider meetingProvider) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.teacherId = teacherId;
		this.lessonId = lessonId;
		this.title = title;
		this.description = description;
		this.scheduledStart = scheduledStart;
		this.scheduledEnd = scheduledEnd;
		this.status = ClassSessionStatus.SCHEDULED;
		this.meetingProvider = meetingProvider;
		this.providerStatus = ClassSessionProviderStatus.PENDING;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getTeacherId() {
		return teacherId;
	}

	public UUID getLessonId() {
		return lessonId;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public Instant getScheduledStart() {
		return scheduledStart;
	}

	public Instant getScheduledEnd() {
		return scheduledEnd;
	}

	public ClassSessionStatus getStatus() {
		return status;
	}

	public MeetingProvider getMeetingProvider() {
		return meetingProvider;
	}

	public ClassSessionProviderStatus getProviderStatus() {
		return providerStatus;
	}

	public String getProviderReference() {
		return providerReference;
	}

	public String getProviderFailureReason() {
		return providerFailureReason;
	}

	// ------------------------------------------------------------------
	// Editable-while-SCHEDULED fields.
	// ------------------------------------------------------------------

	/**
	 * Reschedules/retitles this session - legal only while {@link
	 * ClassSessionStatus#SCHEDULED} (Wave 4 plan §4's {@code PATCH
	 * /class-sessions/{id}} contract).
	 * @throws IllegalStateException if {@link #status} is not currently
	 * {@code SCHEDULED}.
	 */
	public void reschedule(String title, String description, Instant scheduledStart, Instant scheduledEnd) {
		requireScheduled();
		this.title = title;
		this.description = description;
		this.scheduledStart = scheduledStart;
		this.scheduledEnd = scheduledEnd;
	}

	// ------------------------------------------------------------------
	// Provisioning-outcome methods - independent of ClassSessionStatus.
	// ------------------------------------------------------------------

	/** Clears any prior failure reason - a session can move FAILED -> PROVISIONED on a successful retry. */
	public void markProvisioned(String providerReference) {
		this.providerStatus = ClassSessionProviderStatus.PROVISIONED;
		this.providerReference = providerReference;
		this.providerFailureReason = null;
	}

	public void markProvisioningFailed(String reason) {
		this.providerStatus = ClassSessionProviderStatus.FAILED;
		this.providerFailureReason = reason;
	}

	/** @return {@code true} if a (re)provisioning attempt is legal - i.e. not already {@code PROVISIONED}. */
	public boolean isRetryable() {
		return providerStatus != ClassSessionProviderStatus.PROVISIONED;
	}

	// ------------------------------------------------------------------
	// ClassSessionStatus transitions - independent of provisioning outcome.
	// ------------------------------------------------------------------

	/** @throws IllegalStateException if {@link #status} is not currently {@code SCHEDULED}. */
	public void start() {
		requireScheduled();
		this.status = ClassSessionStatus.LIVE;
	}

	/** @throws IllegalStateException if {@link #status} is not currently {@code LIVE}. */
	public void complete() {
		if (this.status != ClassSessionStatus.LIVE) {
			throw new IllegalStateException(
					"Cannot complete a class session that is not currently LIVE (current status: " + this.status + ")");
		}
		this.status = ClassSessionStatus.COMPLETED;
	}

	/** @throws IllegalStateException if {@link #status} is currently {@code COMPLETED} or {@code CANCELLED}. */
	public void cancel() {
		if (this.status == ClassSessionStatus.COMPLETED || this.status == ClassSessionStatus.CANCELLED) {
			throw new IllegalStateException(
					"Cannot cancel a class session that is already " + this.status);
		}
		this.status = ClassSessionStatus.CANCELLED;
	}

	private void requireScheduled() {
		if (this.status != ClassSessionStatus.SCHEDULED) {
			throw new IllegalStateException(
					"This action requires the class session to be currently SCHEDULED (current status: " + this.status
							+ ")");
		}
	}

}
