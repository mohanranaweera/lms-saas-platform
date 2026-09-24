package com.lms.liveclassmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code class_session_recording} (V49). Extends {@link
 * TimestampedEntity} (not {@link com.lms.common.persistence.Auditable}) -
 * V49 defines only {@code created_at}/{@code updated_at} for this table, no
 * {@code created_by}/{@code updated_by} (it is written exclusively by the
 * webhook-driven {@code LiveClassWebhookProcessingService}, which has no
 * authenticated human actor to attribute the write to).
 *
 * <p>At most one row per {@code sessionId} (V49's {@code
 * uq_class_session_recording_tenant_session}) - a re-delivered {@code
 * "recording.completed"} webhook event updates this row in place via {@link
 * #markAvailable}, never inserts a second row.
 */
@Entity
@Table(name = "class_session_recording")
public class ClassSessionRecording extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	@Column(name = "status", nullable = false, length = 10)
	private ClassSessionRecordingStatus status;

	@Column(name = "provider_recording_reference")
	private String providerRecordingReference;

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	protected ClassSessionRecording() {
	}

	public ClassSessionRecording(UUID tenantId, UUID sessionId) {
		this.tenantId = tenantId;
		this.sessionId = sessionId;
		this.status = ClassSessionRecordingStatus.PENDING;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public ClassSessionRecordingStatus getStatus() {
		return status;
	}

	public String getProviderRecordingReference() {
		return providerRecordingReference;
	}

	public Integer getDurationSeconds() {
		return durationSeconds;
	}

	public void markAvailable(String providerRecordingReference, Integer durationSeconds) {
		this.status = ClassSessionRecordingStatus.AVAILABLE;
		this.providerRecordingReference = providerRecordingReference;
		this.durationSeconds = durationSeconds;
	}

	public void markFailed() {
		this.status = ClassSessionRecordingStatus.FAILED;
	}

}
