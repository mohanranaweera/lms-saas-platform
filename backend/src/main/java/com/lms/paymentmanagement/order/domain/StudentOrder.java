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
import org.hibernate.annotations.Generated;

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

	/**
	 * Tenant-local order number (V100), assigned by the BEFORE INSERT trigger
	 * from {@code student_order_number_counter} - but only when the INSERT leaves
	 * it NULL. Never written by the application (insertable/updatable = false);
	 * Hibernate reads the assigned value back after the insert. A primitive that
	 * was inserted used to send 0, so every new order got number 0 and the second
	 * one in a tenant hit {@code uq_student_order_tenant_order_number}.
	 */
	@Generated
	@Column(name = "order_number", insertable = false, updatable = false)
	private Long orderNumber;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	/**
	 * The single course of the order. V90: {@code null} only for a
	 * multi-course payment-composer (line) order - each line then carries its
	 * own {@code course_id} (V90 {@code ck_student_order_course_or_lines}).
	 */
	@Column(name = "course_id", updatable = false)
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

	/**
	 * Wave 6 (§3.3/§4) optional, client-generated dedup key (V52) - mirrors
	 * {@code PaymentRefund.idempotencyKey}'s (V20) exact shape/rationale. A
	 * repeated {@code OrderService#createOrder} checkout submission for the
	 * same student carrying the same {@code (tenantId, studentId,
	 * idempotencyKey)} replays this row instead of creating a second order.
	 * {@code null} for every caller that does not supply one - existing
	 * behavior is unchanged in that case.
	 */
	@Column(name = "idempotency_key", updatable = false)
	private UUID idempotencyKey;

	/**
	 * KLASS-T4c (V87, D06/D07) fee snapshot of a payment-composer (line) order:
	 * all three amounts set together (with {@code amount = subtotal + fee -
	 * discount}, V87 {@code ck_student_order_fee_snapshot}) or all {@code null}
	 * for a legacy single-amount order. Never changed after insert.
	 */
	@Column(name = "subtotal_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal subtotalAmount;

	@Column(name = "facility_fee_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal facilityFeeAmount;

	@Column(name = "discount_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal discountAmount;

	/** e.g. {@code PER_LINE:100.00} / {@code PER_ORDER:200.00} / {@code NONE}; {@code null} for legacy orders. */
	@Column(name = "fee_policy_snapshot", updatable = false, length = 100)
	private String feePolicySnapshot;

	/** V90: total postage of a line order ({@code null} for legacy and pre-V90 line orders). */
	@Column(name = "postage_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal postageAmount;

	/** V90 delivery snapshot (set only when postage was selected); never changed after insert. */
	@Column(name = "delivery_recipient_name", updatable = false, length = 255)
	private String deliveryRecipientName;

	@Column(name = "delivery_address_line", updatable = false, length = 255)
	private String deliveryAddressLine;

	@Column(name = "delivery_city", updatable = false, length = 100)
	private String deliveryCity;

	@Column(name = "delivery_phone", updatable = false, length = 30)
	private String deliveryPhone;

	protected StudentOrder() {
	}

	/**
	 * KLASS-T4c: a payment-composer order (one course, many lines). {@code
	 * amount} is derived here as {@code subtotal + facilityFee - discount} so it
	 * can never disagree with the snapshot.
	 */
	public static StudentOrder forLines(UUID tenantId, UUID studentId, UUID courseId, String currency,
			BigDecimal subtotal, BigDecimal facilityFee, BigDecimal discount, String feePolicySnapshot,
			UUID idempotencyKey) {
		return forLines(tenantId, studentId, courseId, currency, subtotal, facilityFee, null, discount,
				feePolicySnapshot, idempotencyKey, null);
	}

	/**
	 * V90: a payment-composer order over one or more courses ({@code courseId}
	 * {@code null} when it spans several). {@code amount = subtotal +
	 * facilityFee + postage - discount} (V90 {@code
	 * ck_student_order_fee_snapshot}); {@code delivery} is the address snapshot
	 * ({@code null} when no postage was selected).
	 */
	public static StudentOrder forLines(UUID tenantId, UUID studentId, UUID courseId, String currency,
			BigDecimal subtotal, BigDecimal facilityFee, BigDecimal postage, BigDecimal discount,
			String feePolicySnapshot, UUID idempotencyKey, DeliverySnapshot delivery) {
		BigDecimal amount = subtotal.add(facilityFee).add(postage == null ? BigDecimal.ZERO : postage)
			.subtract(discount);
		StudentOrder order = new StudentOrder(tenantId, studentId, courseId, amount, currency, null, idempotencyKey);
		order.subtotalAmount = subtotal;
		order.facilityFeeAmount = facilityFee;
		order.postageAmount = postage;
		order.discountAmount = discount;
		order.feePolicySnapshot = feePolicySnapshot;
		if (delivery != null) {
			order.deliveryRecipientName = delivery.recipientName();
			order.deliveryAddressLine = delivery.addressLine();
			order.deliveryCity = delivery.city();
			order.deliveryPhone = delivery.phone();
		}
		return order;
	}

	/** V90 delivery address snapshot of an order with postage (copied from the student's billing address). */
	public record DeliverySnapshot(String recipientName, String addressLine, String city, String phone) {

	}

	public StudentOrder(UUID tenantId, UUID studentId, UUID courseId, BigDecimal amount, String currency) {
		this(tenantId, studentId, courseId, amount, currency, null, null);
	}

	public StudentOrder(UUID tenantId, UUID studentId, UUID courseId, BigDecimal amount, String currency,
			UUID billingPeriodId) {
		this(tenantId, studentId, courseId, amount, currency, billingPeriodId, null);
	}

	public StudentOrder(UUID tenantId, UUID studentId, UUID courseId, BigDecimal amount, String currency,
			UUID billingPeriodId, UUID idempotencyKey) {
		this.tenantId = tenantId;
		this.studentId = studentId;
		this.courseId = courseId;
		this.amount = amount;
		this.currency = currency;
		this.billingPeriodId = billingPeriodId;
		this.status = OrderStatus.PLACED;
		this.idempotencyKey = idempotencyKey;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	/** 0 only before the order is saved (the database assigns the real number). */
	public long getOrderNumber() {
		return orderNumber == null ? 0L : orderNumber;
	}

	public String getDisplayOrderNumber() {
		return "ORD-" + String.format(java.util.Locale.ROOT, "%06d", getOrderNumber());
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

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}

	public BigDecimal getSubtotalAmount() {
		return subtotalAmount;
	}

	public BigDecimal getFacilityFeeAmount() {
		return facilityFeeAmount;
	}

	public BigDecimal getDiscountAmount() {
		return discountAmount;
	}

	public String getFeePolicySnapshot() {
		return feePolicySnapshot;
	}

	public BigDecimal getPostageAmount() {
		return postageAmount;
	}

	/** The delivery snapshot, or {@code null} when the order has none (no postage / legacy). */
	public DeliverySnapshot getDelivery() {
		if (deliveryAddressLine == null && deliveryCity == null && deliveryRecipientName == null
				&& deliveryPhone == null) {
			return null;
		}
		return new DeliverySnapshot(deliveryRecipientName, deliveryAddressLine, deliveryCity, deliveryPhone);
	}

	/** {@code true} for a payment-composer order (fee snapshot present), {@code false} for a legacy order. */
	public boolean isLineOrder() {
		return subtotalAmount != null;
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
