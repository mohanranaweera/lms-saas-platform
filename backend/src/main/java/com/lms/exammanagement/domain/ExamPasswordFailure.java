package com.lms.exammanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code exam_password_failure} (V113): one wrong exam password entered by a student. Backs
 * the 5-per-minute-per-student-per-exam limit; never stores the attempted value.
 */
@Entity
@Immutable
@Table(name = "exam_password_failure")
public class ExamPasswordFailure extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "failed_at", nullable = false, updatable = false)
	private Instant failedAt;

	protected ExamPasswordFailure() {
	}

	public ExamPasswordFailure(UUID tenantId, UUID examId, UUID studentId, Instant failedAt) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.studentId = studentId;
		this.failedAt = failedAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public Instant getFailedAt() {
		return failedAt;
	}

}
