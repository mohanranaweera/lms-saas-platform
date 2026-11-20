package com.lms.tutemanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code tute_fulfillment} (V121, TUTE-01 section 4): one student's copy of one tute. Rows are
 * inserted only by the race-safe {@code INSERT ... ON CONFLICT DO NOTHING} in {@code
 * TuteFulfillmentWrites}; afterwards only the PENDING -> DISPATCHED / CANCELLED transitions, the
 * PENDING method change and the hold flag change, each under the row lock. Terminal rows are
 * never changed again (except the "payment reversed after dispatch" flag) and never deleted.
 */
@Entity
@Table(name = "tute_fulfillment")
public class TuteFulfillment extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "tute_id", nullable = false, updatable = false)
	private UUID tuteId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private FulfillmentStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false, length = 12)
	private FulfillmentMethod method;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, updatable = false, length = 10)
	private FulfillmentSource source;

	@Column(name = "source_payment_id", updatable = false)
	private UUID sourcePaymentId;

	@Column(name = "source_order_id", updatable = false)
	private UUID sourceOrderId;

	@Column(name = "tute_name", nullable = false, updatable = false, length = 200)
	private String tuteName;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_type", nullable = false, updatable = false, length = 30)
	private TutePaymentType paymentType;

	@Column(name = "price", updatable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "order_delivery_name", updatable = false)
	private String orderDeliveryName;

	@Column(name = "order_delivery_address_line", updatable = false)
	private String orderDeliveryAddressLine;

	@Column(name = "order_delivery_city", updatable = false, length = 100)
	private String orderDeliveryCity;

	@Column(name = "order_delivery_phone", updatable = false, length = 30)
	private String orderDeliveryPhone;

	@Column(name = "payment_reversed_at")
	private Instant paymentReversedAt;

	@Column(name = "hold_released_at")
	private Instant holdReleasedAt;

	@Column(name = "hold_released_by")
	private UUID holdReleasedBy;

	@Column(name = "dispatched_at")
	private Instant dispatchedAt;

	@Column(name = "dispatched_by")
	private UUID dispatchedBy;

	@Column(name = "dispatch_recipient_name")
	private String dispatchRecipientName;

	@Column(name = "dispatch_address_line")
	private String dispatchAddressLine;

	@Column(name = "dispatch_address_line_2")
	private String dispatchAddressLine2;

	@Column(name = "dispatch_city", length = 100)
	private String dispatchCity;

	@Column(name = "dispatch_postal_code", length = 20)
	private String dispatchPostalCode;

	@Column(name = "dispatch_phone", length = 50)
	private String dispatchPhone;

	@Enumerated(EnumType.STRING)
	@Column(name = "dispatch_address_source", length = 10)
	private AddressSource dispatchAddressSource;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancelled_by")
	private UUID cancelledBy;

	@Column(name = "cancel_reason", length = 500)
	private String cancelReason;

	protected TuteFulfillment() {
	}

	/** The source payment was refunded / charged back and staff have not released the hold. */
	public boolean isHeld() {
		return paymentReversedAt != null && holdReleasedAt == null;
	}

	public void dispatch(UUID actorId, Instant now, String recipientName, String addressLine, String addressLine2,
			String city, String postalCode, String phone, AddressSource addressSource) {
		requirePending();
		this.status = FulfillmentStatus.DISPATCHED;
		this.dispatchedAt = now;
		this.dispatchedBy = actorId;
		this.dispatchRecipientName = recipientName;
		this.dispatchAddressLine = addressLine;
		this.dispatchAddressLine2 = addressLine2;
		this.dispatchCity = city;
		this.dispatchPostalCode = postalCode;
		this.dispatchPhone = phone;
		this.dispatchAddressSource = addressSource;
	}

	public void cancel(UUID actorId, Instant now, String reason) {
		requirePending();
		this.status = FulfillmentStatus.CANCELLED;
		this.cancelledAt = now;
		this.cancelledBy = actorId;
		this.cancelReason = reason;
	}

	public void changeMethod(FulfillmentMethod method) {
		requirePending();
		this.method = method;
	}

	/** Sets the reversal flag once; a later reversal of the same payment changes nothing. */
	public boolean markPaymentReversed(Instant now) {
		if (paymentReversedAt != null || status == FulfillmentStatus.CANCELLED) {
			return false;
		}
		this.paymentReversedAt = now;
		return true;
	}

	public void releaseHold(UUID actorId, Instant now) {
		requirePending();
		this.holdReleasedAt = now;
		this.holdReleasedBy = actorId;
	}

	private void requirePending() {
		if (status != FulfillmentStatus.PENDING) {
			throw new IllegalStateException("Fulfillment is " + status);
		}
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getTuteId() {
		return tuteId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public FulfillmentStatus getStatus() {
		return status;
	}

	public FulfillmentMethod getMethod() {
		return method;
	}

	public FulfillmentSource getSource() {
		return source;
	}

	public UUID getSourcePaymentId() {
		return sourcePaymentId;
	}

	public UUID getSourceOrderId() {
		return sourceOrderId;
	}

	public String getTuteName() {
		return tuteName;
	}

	public TutePaymentType getPaymentType() {
		return paymentType;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public String getCurrency() {
		return currency;
	}

	public String getOrderDeliveryName() {
		return orderDeliveryName;
	}

	public String getOrderDeliveryAddressLine() {
		return orderDeliveryAddressLine;
	}

	public String getOrderDeliveryCity() {
		return orderDeliveryCity;
	}

	public String getOrderDeliveryPhone() {
		return orderDeliveryPhone;
	}

	public Instant getPaymentReversedAt() {
		return paymentReversedAt;
	}

	public Instant getHoldReleasedAt() {
		return holdReleasedAt;
	}

	public Instant getDispatchedAt() {
		return dispatchedAt;
	}

	public UUID getDispatchedBy() {
		return dispatchedBy;
	}

	public String getDispatchRecipientName() {
		return dispatchRecipientName;
	}

	public String getDispatchAddressLine() {
		return dispatchAddressLine;
	}

	public String getDispatchAddressLine2() {
		return dispatchAddressLine2;
	}

	public String getDispatchCity() {
		return dispatchCity;
	}

	public String getDispatchPostalCode() {
		return dispatchPostalCode;
	}

	public String getDispatchPhone() {
		return dispatchPhone;
	}

	public AddressSource getDispatchAddressSource() {
		return dispatchAddressSource;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public String getCancelReason() {
		return cancelReason;
	}

}
