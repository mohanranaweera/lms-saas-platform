package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Cumulative per-(student, video) watch state (Wave 5, V51, PAR-20-02) - the
 * running counters {@code max_views_per_student}/no-seeking-ahead/{@code
 * max_watch_duration_seconds} enforcement reads and mutates.
 *
 * <p>Deliberately extends {@link TimestampedEntity} (not {@link
 * com.lms.common.persistence.Auditable}): V51's {@code video_watch_progress}
 * table has {@code created_at}/{@code updated_at} but no {@code created_by}/
 * {@code updated_by} columns - mirrors {@code TimestampedEntity}'s own
 * javadoc precedent exactly.
 *
 * <p>The sole intentional exception to this schema's append-only financial/
 * audit tables (see V51's own header comment) - mutable running state,
 * updated in place, not a financial/audit trail.
 */
@Entity
@Table(name = "video_watch_progress")
public class VideoWatchProgress extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "video_asset_id", nullable = false, updatable = false)
	private UUID videoAssetId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "views_count", nullable = false)
	private Integer viewsCount;

	@Column(name = "total_watched_seconds", nullable = false)
	private Integer totalWatchedSeconds;

	@Column(name = "furthest_position_seconds", nullable = false)
	private Integer furthestPositionSeconds;

	@Column(name = "last_watched_at")
	private Instant lastWatchedAt;

	protected VideoWatchProgress() {
	}

	public VideoWatchProgress(UUID tenantId, UUID videoAssetId, UUID studentId) {
		this.tenantId = tenantId;
		this.videoAssetId = videoAssetId;
		this.studentId = studentId;
		this.viewsCount = 0;
		this.totalWatchedSeconds = 0;
		this.furthestPositionSeconds = 0;
	}

	public void recordHeartbeat(int watchedDeltaSeconds, int newFurthestPositionSeconds, Instant now) {
		this.totalWatchedSeconds += watchedDeltaSeconds;
		this.furthestPositionSeconds = Math.max(this.furthestPositionSeconds, newFurthestPositionSeconds);
		this.lastWatchedAt = now;
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

	public Integer getViewsCount() {
		return viewsCount;
	}

	public Integer getTotalWatchedSeconds() {
		return totalWatchedSeconds;
	}

	public Integer getFurthestPositionSeconds() {
		return furthestPositionSeconds;
	}

	public Instant getLastWatchedAt() {
		return lastWatchedAt;
	}

}
