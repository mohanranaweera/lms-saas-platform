package com.lms.paymentmanagement.order.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code payment-management}'s "intent to buy" aggregate (PAY-1), mapped 1:1
 * onto {@code student_order} (V19). Named {@code StudentOrder} rather than
 * {@code Order} purely to avoid any confusion with {@code java.util}/SQL
 * reserved-word baggage - the table itself is named {@code student_order} for
 * the SQL-reserved-word reason explained in V19's header comment.
 *
 * <p>{@code studentId}/{@code courseId} are OPAQUE cross-domain ids only -
 * never a JPA association across the module boundary (per
 * {@code .claude/rules/architecture.md}), though both are still
 * schema-enforced via V19's composite FKs to {@code tenant_user}/{@code
 * course}. {@code amount}/{@code currency} are a snapshot of {@code
 * course.price} taken at creation time by {@code OrderService#createOrder} -
 * never re-read from the course later.
 *
 * <p>{@code status} is never read by any enrollment-activation code path
 * (per plan §5's PAY-1 acceptance criterion) - only {@code payment.status}
 * reaching {@code CONFIRMED} may justify activation.
 */
@Entity
@Table(name = "student_order")
public class StudentOrder extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OrderStatus status;

	/**
	 * {@code student_order.billing_period_id} (V40, Wave 2) - an OPAQUE,
	 * purely-traceability reference to the {@code course_billing_period} row
	 * this order's {@code amount} snapshot was resolved from, for {@code
	 * MONTHLY}/{@code SESSION} pricing only. {@code null} for every other
	 * pricing model (mirrors {@code studentId}/{@code courseId}'s "opaque id,
	 * never a JPA association across the module boundary" convention). Never
	 * mutated after construction - set once, at order-creation time, exactly
	 * like {@code amount}/{@code currency}.
	 */
	@Column(name = "billing_period_id", updatable = false)
	private UUID billingPeriodId;

	protected StudentOrder() {
	}

	public StudentOrder(UUID tenantId, UUID studentId, UUID courseId, BigDecimal amount, String currency) {
		this(tenantId, studentId, courseId, amount, currency, null);
	}

	public StudentOrder(UUID tenantId, UUID studentId, UUID courseId, BigDecimal amount, String currency,
			UUID billingPeriodId) {
		this.tenantId = tenantId;
		this.studentId = studentId;
		this.courseId = courseId;
		this.amount = amount;
		this.currency = currency;
		this.billingPeriodId = billingPeriodId;
		this.status = OrderStatus.PLACED;
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

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public UUID getBillingPeriodId() {
		return billingPeriodId;
	}

	/**
	 * Called exclusively by {@code PaymentWriteService#createPendingPayment}
	 * when the first payment attempt is initiated for this order. A true
	 * no-op if already {@code PENDING} (a retried/second payment attempt on
	 * the same order does not need to re-transition it).
	 */
	public void markPending() {
		if (status == OrderStatus.PLACED) {
			status = OrderStatus.PENDING;
		}
	}

}
