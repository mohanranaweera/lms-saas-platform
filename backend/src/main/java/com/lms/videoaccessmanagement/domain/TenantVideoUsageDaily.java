package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A tenant's video provider usage for one {@code America/Los_Angeles} day
 * ({@code tenant_video_usage_daily}, V72 - VIDEO Phase 4, Q-014). Counters
 * are only changed by the atomic native statements in {@code
 * TenantVideoUsageDailyRepository} - never read-modify-write through this
 * entity - so concurrent uploads cannot lose an increment or overspend the
 * budget.
 */
@Entity
@Table(name = "tenant_video_usage_daily")
public class TenantVideoUsageDaily extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "usage_date", nullable = false, updatable = false)
	private LocalDate usageDate;

	@Column(name = "uploads", nullable = false, insertable = false, updatable = false)
	private int uploads;

	@Column(name = "bytes", nullable = false, insertable = false, updatable = false)
	private long bytes;

	@Column(name = "api_units", nullable = false, insertable = false, updatable = false)
	private long apiUnits;

	protected TenantVideoUsageDaily() {
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public LocalDate getUsageDate() {
		return usageDate;
	}

	public int getUploads() {
		return uploads;
	}

	public long getBytes() {
		return bytes;
	}

	public long getApiUnits() {
		return apiUnits;
	}

}
