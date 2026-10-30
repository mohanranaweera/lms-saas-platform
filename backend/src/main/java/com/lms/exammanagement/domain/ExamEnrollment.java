package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code exam_enrollment} (V113, ADR-032): a student may sit a Klass exam. Separate from free
 * course participation, which exams never create, change or cancel. Rows are inserted only by
 * the race-safe {@code INSERT ... ON CONFLICT DO NOTHING} in {@code ExamEnrollmentWrites} and
 * never updated; they are deleted only by an audited staff removal or refund handling, never
 * while an attempt exists (schema-enforced).
 */
@Entity
@Immutable
@Table(name = "exam_enrollment")
public class ExamEnrollment extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, updatable = false, length = 12)
	private ExamEnrollmentSource source;

	@Column(name = "payment_id", updatable = false)
	private UUID paymentId;

	@Column(name = "free_card_id", updatable = false)
	private UUID freeCardId;

	protected ExamEnrollment() {
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getExamId() {
		return examId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public ExamEnrollmentSource getSource() {
		return source;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public UUID getFreeCardId() {
		return freeCardId;
	}

}
