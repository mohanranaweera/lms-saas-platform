package com.lms.attendancemanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code attendance_sheet} (V56) - the parent of every
 * {@link AttendanceRecord} (Wave 8: {@code ClassSession -> AttendanceSheet ->
 * AttendanceRecord}). See V56's header for the full tenant-isolation and
 * course-consistency FK rationale.
 *
 * <p>Rows are only ever created through {@code AttendanceSheetRepository}'s
 * race-safe native {@code INSERT ... ON CONFLICT DO NOTHING} methods (or V56's
 * legacy backfill), never via {@code save()} of a new instance, so two
 * concurrent first marks of the same session can never produce two sheets.
 * Every column is immutable after insert - a sheet has no lifecycle of its
 * own; the teaching session's lifecycle lives on {@code class_session}.
 * {@code classSessionId}/{@code lessonId}/{@code courseId} are opaque
 * cross-domain ids, never JPA associations.
 */
@Entity
@Table(name = "attendance_sheet")
public class AttendanceSheet extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, updatable = false, length = 20)
	private AttendanceSheetSource source;

	@Column(name = "class_session_id", updatable = false)
	private UUID classSessionId;

	@Column(name = "lesson_id", updatable = false)
	private UUID lessonId;

	protected AttendanceSheet() {
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

	public AttendanceSheetSource getSource() {
		return source;
	}

	/** Non-null iff {@link #getSource()} is {@link AttendanceSheetSource#CLASS_SESSION}. */
	public UUID getClassSessionId() {
		return classSessionId;
	}

	/** Non-null iff {@link #getSource()} is {@link AttendanceSheetSource#LEGACY_LESSON}. */
	public UUID getLessonId() {
		return lessonId;
	}

}
