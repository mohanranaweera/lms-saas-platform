package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Minimal activation-slice aggregate (plan §21 item 1), mapped 1:1 onto
 * {@code enrollment} (V19). {@code studentId}/{@code courseId}/{@code
 * activatingPaymentId}/{@code activatingSlipId} are opaque cross-domain ids
 * only - never a JPA association across the module boundary - though all are
 * still schema-enforced via V19's composite FKs.
 *
 * <p>Exactly one of {@code activatingPaymentId}/{@code activatingSlipId} is
 * ever set on a given row - {@link #fromConfirmedPayment} and (MVP-011)
 * {@link #fromApprovedSlip} are the only two factories this module ships,
 * both enforcing V19's {@code ck_enrollment_exactly_one_activation_source}
 * invariant at construction time too, not just at the DB level.
 *
 * <p>Written EXCLUSIVELY by {@code EnrollmentActivationService} - see
 * {@code EnrollmentActivationApi}'s javadoc for the two approved,
 * structurally-only call sites (a verified webhook confirming a payment, or
 * an approved manual payment slip). No repository method exposes
 * update/delete (see {@code EnrollmentRepository}).
 */
@Entity
@Table(name = "enrollment")
public class Enrollment extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "activating_payment_id", updatable = false)
	private UUID activatingPaymentId;

	@Column(name = "activating_slip_id", updatable = false)
	private UUID activatingSlipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false, length = 20)
	private EnrollmentStatus status;

	@Column(name = "activated_at", nullable = false, updatable = false)
	private Instant activatedAt;

	protected Enrollment() {
	}

	private Enrollment(UUID tenantId, UUID studentId, UUID courseId, UUID activatingPaymentId,
			UUID activatingSlipId) {
		boolean hasPayment = activatingPaymentId != null;
		boolean hasSlip = activatingSlipId != null;
		if (hasPayment == hasSlip) {
			throw new IllegalArgumentException(
					"Exactly one of activatingPaymentId/activatingSlipId must be set, never both or neither");
		}
		this.tenantId = tenantId;
		this.studentId = studentId;
		this.courseId = courseId;
		this.activatingPaymentId = activatingPaymentId;
		this.activatingSlipId = activatingSlipId;
		this.status = EnrollmentStatus.ACTIVE;
		this.activatedAt = Instant.now();
	}

	/** The confirmed-payment activation path - see class javadoc. */
	public static Enrollment fromConfirmedPayment(UUID tenantId, UUID studentId, UUID courseId, UUID paymentId) {
		return new Enrollment(tenantId, studentId, courseId, paymentId, null);
	}

	/**
	 * The approved-manual-slip activation path (MVP-011/SLIP-3). Mirrors
	 * {@link #fromConfirmedPayment} exactly - the shared private constructor
	 * still enforces {@code ck_enrollment_exactly_one_activation_source} at
	 * construction time, not just at the DB level.
	 */
	public static Enrollment fromApprovedSlip(UUID tenantId, UUID studentId, UUID courseId, UUID slipId) {
		return new Enrollment(tenantId, studentId, courseId, null, slipId);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getActivatingPaymentId() {
		return activatingPaymentId;
	}

	public UUID getActivatingSlipId() {
		return activatingSlipId;
	}

	public EnrollmentStatus getStatus() {
		return status;
	}

	public Instant getActivatedAt() {
		return activatedAt;
	}

}
