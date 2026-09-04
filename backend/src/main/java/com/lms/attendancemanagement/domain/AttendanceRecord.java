package com.lms.attendancemanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code attendance_record} (V25) - see that migration's own
 * header for the full tenant-isolation/FK rationale. {@code courseId}/{@code
 * sessionId}/{@code studentId}/{@code markedBy} are opaque cross-domain ids
 * only - never a JPA association across the module boundary (per {@code
 * .claude/rules/architecture.md}), though all are still schema-enforced via
 * V25's composite {@code (tenant_id, ...)} FKs.
 *
 * <p>Extends {@link Auditable} (not the bare {@code BaseEntity}) for the
 * generic {@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code
 * updatedBy} provenance columns - {@code markedBy}/{@code markedAt} are kept
 * as separate, explicit domain columns alongside them (never inferred from
 * {@code Auditable}'s fields), per V25's migration header: {@code markedBy}
 * is domain-meaningful and must be re-settable by a different staff member on
 * a re-mark, independent of whatever the generic auditing listener records.
 *
 * <p><b>Mutability.</b> Unlike this schema's append-only payment/ledger/
 * audit-log tables, this entity IS mutable by design - {@link #remark} is the
 * one legal in-place update, applied by {@code AttendanceMarkingService} when
 * a (session, student) pair that already has a row is re-marked (plan §7
 * boxed note, product-owner confirmed). There is no separate change-history
 * row; the prior {@code status}/{@code markedBy}/{@code markedAt} are
 * overwritten, not preserved (plan §21).
 */
@Entity
@Table(name = "attendance_record")
public class AttendanceRecord extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private AttendanceStatus status;

	@Column(name = "marked_by", nullable = false)
	private UUID markedBy;

	@Column(name = "marked_at", nullable = false)
	private Instant markedAt;

	protected AttendanceRecord() {
	}

	/**
	 * The only construction path - a brand-new (tenant, session, student)
	 * row. {@code courseId} MUST already have been derived server-side from
	 * {@code CourseLookupApi.resolveLessonOwnership(sessionId).courseId()}
	 * by the caller ({@code AttendanceMarkingService}); this constructor
	 * trusts its argument, it does not itself re-derive or validate it.
	 */
	public AttendanceRecord(UUID tenantId, UUID courseId, UUID sessionId, UUID studentId, AttendanceStatus status,
			UUID markedBy, Instant markedAt) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.sessionId = sessionId;
		this.studentId = studentId;
		this.status = status;
		this.markedBy = markedBy;
		this.markedAt = markedAt;
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

	public UUID getSessionId() {
		return sessionId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public AttendanceStatus getStatus() {
		return status;
	}

	public UUID getMarkedBy() {
		return markedBy;
	}

	public Instant getMarkedAt() {
		return markedAt;
	}

	/**
	 * Re-marks this row in place - updates {@code status}/{@code markedBy}/
	 * {@code markedAt} only, never {@code tenantId}/{@code courseId}/{@code
	 * sessionId}/{@code studentId}, which stay fixed for the life of the row
	 * (the {@code uq_attendance_record_tenant_session_student} constraint's
	 * whole point). The only mutation this entity permits after construction.
	 */
	public void remark(AttendanceStatus status, UUID markedBy, Instant markedAt) {
		this.status = status;
		this.markedBy = markedBy;
		this.markedAt = markedAt;
	}

}
