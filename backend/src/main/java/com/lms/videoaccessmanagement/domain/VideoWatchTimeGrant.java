package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Extra watch time a staff member granted a Student for one video (V80, VIDEO
 * Phase 12). Append-only: every column is immutable and the table's trigger
 * rejects UPDATE/DELETE. The Student's allowed time is the budget snapshot on
 * {@link VideoWatchProgress} plus the sum of their grants.
 */
@Entity
@Table(name = "video_watch_time_grant")
public class VideoWatchTimeGrant extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "video_asset_id", nullable = false, updatable = false)
	private UUID videoAssetId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "extra_seconds", nullable = false, updatable = false)
	private int extraSeconds;

	@Column(name = "reason", nullable = false, updatable = false)
	private String reason;

	@Column(name = "granted_by", nullable = false, updatable = false)
	private UUID grantedBy;

	@Column(name = "granted_at", nullable = false, updatable = false)
	private Instant grantedAt;

	protected VideoWatchTimeGrant() {
	}

	public VideoWatchTimeGrant(UUID tenantId, UUID videoAssetId, UUID studentId, int extraSeconds, String reason,
			UUID grantedBy, Instant grantedAt) {
		this.tenantId = tenantId;
		this.videoAssetId = videoAssetId;
		this.studentId = studentId;
		this.extraSeconds = extraSeconds;
		this.reason = reason;
		this.grantedBy = grantedBy;
		this.grantedAt = grantedAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getVideoAssetId() {
		return videoAssetId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public int getExtraSeconds() {
		return extraSeconds;
	}

	public String getReason() {
		return reason;
	}

	public UUID getGrantedBy() {
		return grantedBy;
	}

	public Instant getGrantedAt() {
		return grantedAt;
	}

}
