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
 * {@code enrollment} (V19, extended by V22 for MVP-012's lineage/expiry
 * columns). {@code studentId}/{@code courseId}/{@code activatingPaymentId}/
 * {@code activatingSlipId} are opaque cross-domain ids only - never a JPA
 * association across the module boundary - though all are still
 * schema-enforced via V19's composite FKs.
 *
 * <p>Exactly one of {@code activatingPaymentId}/{@code activatingSlipId} is
 * ever set on a given row - {@link #fromConfirmedPayment}/{@link
 * #fromApprovedSlip}/{@link #reactivatedFromConfirmedPayment}/{@link
 * #reactivatedFromApprovedSlip} are the only four factories this module
 * ships, all funneling through the same private constructor which enforces
 * V19's {@code ck_enrollment_exactly_one_activation_source} invariant at
 * construction time too, not just at the DB level.
 *
 * <h2>Lineage-row model (MVP-012/ADR-013, "Enrollment lineage-row model")</h2>
 * A student may pass through this table more than once over time (activate
 * -&gt; expire -&gt; reactivate -&gt; ...) without ever overwriting the original
 * activation evidence. At most one row per (tenant, student, course) is ever
 * "current" ({@code supersededAt == null}) at a time (schema-enforced by
 * {@code uq_enrollment_tenant_student_course_current}, V22); any number of
 * historical, superseded rows may coexist for the same pair. A reactivation
 * is: {@link #supersede()} the prior current row (its ONLY ever mutation -
 * every other column, including {@code activatingPaymentId}/{@code
 * activatingSlipId}/{@code activatedAt}, stays {@code updatable=false}) and
 * INSERT a brand-new row via {@link #reactivatedFromConfirmedPayment}/{@link
 * #reactivatedFromApprovedSlip}, linked back via {@code
 * reactivatedFromEnrollmentId}.
 *
 * <p>Access currency ({@code ACTIVE}/{@code EXPIRED}) is never stored as an
 * enum value on this row - {@code status} still only ever answers "was this
 * row's activation valid" ({@link EnrollmentStatus#ACTIVE}, unchanged single
 * value). Access currency is always computed live via {@link
 * #isCurrentlyActive(Instant)}, never persisted.
 *
 * <p>Written EXCLUSIVELY by {@code EnrollmentActivationService} - see
 * {@code EnrollmentActivationApi}'s javadoc for the four approved,
 * structurally-only call sites (a verified webhook confirming a payment, an
 * approved manual payment slip, and the reactivation-aware counterpart of
 * each). No repository method exposes update/delete beyond the narrow {@link
 * #supersede()} column (see {@code EnrollmentRepository}).
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

	@Column(name = "access_expires_at", updatable = false)
	private Instant accessExpiresAt;

	/**
	 * Settable exactly once, and only via {@link #supersede()} - never a
	 * public setter, never set at construction time (a brand-new row is
	 * always current when written).
	 */
	@Column(name = "superseded_at")
	private Instant supersededAt;

	@Column(name = "reactivated_from_enrollment_id", updatable = false)
	private UUID reactivatedFromEnrollmentId;

	protected Enrollment() {
	}

	private Enrollment(UUID tenantId, UUID studentId, UUID courseId, UUID activatingPaymentId, UUID activatingSlipId,
			Instant accessExpiresAt, UUID reactivatedFromEnrollmentId) {
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
		this.accessExpiresAt = accessExpiresAt;
		this.reactivatedFromEnrollmentId = reactivatedFromEnrollmentId;
	}

	/**
	 * The confirmed-payment activation path - see class javadoc.
	 * @param accessExpiresAt the course's access window computed from {@code
	 * CourseLookupApi#getAccessDurationDays} at this instant, or {@code null}
	 * for lifetime access.
	 */
	public static Enrollment fromConfirmedPayment(UUID tenantId, UUID studentId, UUID courseId, UUID paymentId,
			Instant accessExpiresAt) {
		return new Enrollment(tenantId, studentId, courseId, paymentId, null, accessExpiresAt, null);
	}

	/**
	 * The approved-manual-slip activation path (MVP-011/SLIP-3). Mirrors
	 * {@link #fromConfirmedPayment} exactly - the shared private constructor
	 * still enforces {@code ck_enrollment_exactly_one_activation_source} at
	 * construction time, not just at the DB level.
	 */
	public static Enrollment fromApprovedSlip(UUID tenantId, UUID studentId, UUID courseId, UUID slipId,
			Instant accessExpiresAt) {
		return new Enrollment(tenantId, studentId, courseId, null, slipId, accessExpiresAt, null);
	}

	/**
	 * The reactivation counterpart of {@link #fromConfirmedPayment}
	 * (MVP-012/ADR-013) - writes a brand-new, current row linked back to the
	 * prior (now-{@link #supersede() superseded}) row via {@code
	 * reactivatedFromEnrollmentId}. Never mutates the prior row's own
	 * activation evidence.
	 */
	public static Enrollment reactivatedFromConfirmedPayment(UUID tenantId, UUID studentId, UUID courseId,
			UUID paymentId, Instant accessExpiresAt, UUID reactivatedFromEnrollmentId) {
		if (reactivatedFromEnrollmentId == null) {
			throw new IllegalArgumentException("reactivatedFromEnrollmentId must not be null for a reactivation row");
		}
		return new Enrollment(tenantId, studentId, courseId, paymentId, null, accessExpiresAt,
				reactivatedFromEnrollmentId);
	}

	/** The reactivation counterpart of {@link #fromApprovedSlip} - see {@link #reactivatedFromConfirmedPayment}. */
	public static Enrollment reactivatedFromApprovedSlip(UUID tenantId, UUID studentId, UUID courseId, UUID slipId,
			Instant accessExpiresAt, UUID reactivatedFromEnrollmentId) {
		if (reactivatedFromEnrollmentId == null) {
			throw new IllegalArgumentException("reactivatedFromEnrollmentId must not be null for a reactivation row");
		}
		return new Enrollment(tenantId, studentId, courseId, null, slipId, accessExpiresAt,
				reactivatedFromEnrollmentId);
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

	public Instant getAccessExpiresAt() {
		return accessExpiresAt;
	}

	public Instant getSupersededAt() {
		return supersededAt;
	}

	public UUID getReactivatedFromEnrollmentId() {
		return reactivatedFromEnrollmentId;
	}

	/**
	 * Pure, unit-testable-in-isolation access-currency computation (plan §7/
	 * §18) - {@code NOT superseded AND (accessExpiresAt IS NULL OR
	 * accessExpiresAt > now)}. Never itself the source of a stored/persisted
	 * update - callers needing the "never enrolled" case (no row at all) or
	 * the lazy expiry-event write handle that themselves ({@code
	 * EnrollmentExpiryService}), since neither is expressible on a single row
	 * in isolation.
	 */
	public boolean isCurrentlyActive(Instant now) {
		return supersededAt == null && (accessExpiresAt == null || accessExpiresAt.isAfter(now));
	}

	/**
	 * The ONLY legal mutation on an otherwise-immutable row (mirrors {@code
	 * PaymentSlip}'s narrow, single-purpose in-place status-update
	 * precedent). Intended to be called only by {@code
	 * EnrollmentActivationService}'s reactivation methods, in the same
	 * transaction as the new row's insert - not enforced as Java
	 * package-private since {@code EnrollmentActivationService} lives in a
	 * different Java package ({@code .service}) than this entity ({@code
	 * .domain}); this is the same visibility trade-off {@code PaymentSlip}'s
	 * own {@code approve}/{@code reject} already accept for the identical
	 * reason.
	 * @throws IllegalStateException if this row is already superseded - a
	 * row must never be superseded twice.
	 */
	public void supersede() {
		if (supersededAt != null) {
			throw new IllegalStateException("Enrollment " + getId() + " is already superseded");
		}
		this.supersededAt = Instant.now();
	}

}
