package com.lms.coursemanagement.calendar.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

/**
 * {@code course_class_schedule_slot} (V116): one weekly row of a {@link
 * CourseClassScheduleRevision} - ISO day of week plus minute-precision local
 * wall-clock start/end on the same day. Append-only like its revision.
 */
@Entity
@Table(name = "course_class_schedule_slot")
public class CourseClassScheduleSlot extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "revision_id", nullable = false, updatable = false)
	private UUID revisionId;

	@Column(name = "day_of_week", nullable = false, updatable = false)
	private short dayOfWeek;

	@Column(name = "start_time", nullable = false, updatable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false, updatable = false)
	private LocalTime endTime;

	protected CourseClassScheduleSlot() {
	}

	public CourseClassScheduleSlot(UUID tenantId, UUID revisionId, DayOfWeek dayOfWeek, LocalTime startTime,
			LocalTime endTime) {
		this.tenantId = tenantId;
		this.revisionId = revisionId;
		this.dayOfWeek = (short) dayOfWeek.getValue();
		this.startTime = startTime;
		this.endTime = endTime;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getRevisionId() {
		return revisionId;
	}

	public DayOfWeek getDayOfWeek() {
		return DayOfWeek.of(dayOfWeek);
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}

}
